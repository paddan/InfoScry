import java.io.BufferedReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.LowerCaseFilterFactory;
import org.apache.lucene.analysis.custom.CustomAnalyzer;
import org.apache.lucene.analysis.icu.ICUFoldingFilterFactory;
import org.apache.lucene.analysis.standard.StandardTokenizerFactory;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;

/**
 * The Lucene half of the pg_search spike: indexes chunks.tsv with InfoScry's analyzer (standard tokenizer,
 * ICU folding, lower case; LuceneIndex.newAnalyzer) and default BM25, and runs queries.tsv through the
 * classic QueryParser with InfoScry's escape fallback (LuceneIndex.parseSafely).
 *
 * Usage: java --class-path <lucene jars> LuceneSide.java <chunks.tsv> <queries.tsv> <probes.txt> <out-dir>
 */
public class LuceneSide {
    static Analyzer analyzer() throws Exception {
        return CustomAnalyzer.builder()
            .withTokenizer(StandardTokenizerFactory.class)
            .addTokenFilter(ICUFoldingFilterFactory.class)
            .addTokenFilter(LowerCaseFilterFactory.class)
            .build();
    }

    static Query parseSafely(Analyzer analyzer, String text) throws ParseException {
        try {
            return new QueryParser("text", analyzer).parse(text);
        } catch (ParseException failure) {
            return new QueryParser("text", analyzer).parse(QueryParser.escape(text));
        }
    }

    static List<String> tokens(Analyzer analyzer, String text) throws Exception {
        List<String> out = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream("text", text)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) out.add(term.toString());
            stream.end();
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        Path chunks = Path.of(args[0]), queries = Path.of(args[1]), probes = Path.of(args[2]), out = Path.of(args[3]);
        Path indexDir = out.resolve("lucene-index");
        Files.createDirectories(indexDir);
        Analyzer analyzer = analyzer();

        long buildStart = System.nanoTime();
        try (FSDirectory dir = FSDirectory.open(indexDir);
             IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE));
             BufferedReader reader = Files.newBufferedReader(chunks, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t", 3);
                Document doc = new Document();
                doc.add(new StringField("id", parts[0], Field.Store.YES));
                doc.add(new TextField("text", parts[2], Field.Store.NO));
                writer.addDocument(doc);
            }
            writer.commit();
        }
        long buildMillis = (System.nanoTime() - buildStart) / 1_000_000;
        long indexBytes;
        try (var files = Files.list(indexDir)) {
            indexBytes = files.mapToLong(p -> p.toFile().length()).sum();
        }

        try (FSDirectory dir = FSDirectory.open(indexDir);
             DirectoryReader reader = DirectoryReader.open(dir);
             PrintWriter hits = new PrintWriter(Files.newBufferedWriter(out.resolve("lucene-hits.tsv")));
             PrintWriter counts = new PrintWriter(Files.newBufferedWriter(out.resolve("lucene-counts.tsv")))) {
            IndexSearcher searcher = new IndexSearcher(reader);
            for (String line : Files.readAllLines(queries)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split("\t");
                Query query = parseSafely(analyzer, parts[2]);
                // Warm up, then take the median of five runs.
                for (int i = 0; i < 3; i++) searcher.search(query, 10);
                long[] runs = new long[5];
                TopDocs top = null;
                for (int i = 0; i < runs.length; i++) {
                    long start = System.nanoTime();
                    top = searcher.search(query, 10);
                    runs[i] = System.nanoTime() - start;
                }
                Arrays.sort(runs);
                int total = searcher.count(query);
                counts.printf("%s\t%d\t%.3f\t%s%n", parts[0], total, runs[2] / 1e6, query.toString("text"));
                int rank = 1;
                for (ScoreDoc hit : top.scoreDocs) {
                    hits.printf("%s\t%d\t%s\t%.6f%n", parts[0], rank++, searcher.storedFields().document(hit.doc).get("id"), hit.score);
                }
            }
        }

        try (PrintWriter tokenOut = new PrintWriter(Files.newBufferedWriter(out.resolve("lucene-tokens.tsv")))) {
            for (String probe : Files.readAllLines(probes)) {
                if (!probe.isBlank()) tokenOut.printf("%s\t%s%n", probe, String.join(" ", tokens(analyzer, probe)));
            }
        }
        Files.writeString(out.resolve("lucene-stats.tsv"), "build_ms\t" + buildMillis + "\nindex_bytes\t" + indexBytes + "\n");
    }
}
