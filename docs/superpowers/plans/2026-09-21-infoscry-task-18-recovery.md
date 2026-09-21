# Task 18: återställningsplan efter misslyckade implementationer

## Syfte och avgränsning

Task 18 ska färdigställas som en verifierad, crash-säker sökindexering och reindexering. Den här planen reparerar den befintliga WIP-grenen och täcker bara Task 18:s sex protokoll: per-dokument-idempotens, stabila content-unit-ID:n, ett komplett index, `index/current`-markören, exklusivt underhåll och säkert generationsbyte. Den lägger inte till senare funktionsfaser.

## Vad som faktiskt har fallerat

### Verifierat med aktuell checkout

- `main` kompilerar (`JAVA_HOME=$(asdf where java) ./gradlew compileKotlin`).
- `compileTestKotlin` fallerar i `src/test/kotlin/infoscry/search/ReindexRecoveryTest.kt`, före någon Task 18-gate eller funktionellt test. Felen är bland annat dubbla/ambigua `SeededArchive.componentN`-metoder, åtkomst till privata `LuceneIndex.use`, saknat `index`, felaktiga argumenttyper, saknade `mutations`/`collectionService`/`close`/`AppPaths` och ett suspend-anrop utanför coroutine.
- Testet definierar manuella `component1`–`component3` i en `data class`, trots att Kotlin redan genererar dem. Det är ett konkret test-fixturefel, inte ett produktkrav.
- Det finns ingen separat `SearchRoutesTest`, `SearchCommandTest` eller `ReindexCommandTest`. Route-, CLI- och JSON-kontrakten är därför inte verifierade även om produktionskoden kompilerar.
- `./gradlew check` och den dokumenterade CLI-rökningen har inte körts efter WIP-commiten. En tidigare wrapper-lock-behörighetsmisslyckning skiljs från den reproducerade Kotlin-kompileringsmissen.
- Fyra implementeringsförsök avbröts av providerfel. WIP samlades från flera försök och committades som `3363e76`, men utan compile-test, gate eller review. Det gör commitens kod till ett verifieringsobjekt, inte ett godkänt resultat.

### Risker i WIP som måste bevisas eller ändras

- `ReindexService` kopierar den gamla Lucene-generationen och går därefter bara igenom dokument som fortfarande finns i SQLite. Ett borttaget dokument kan därför följa med till den nya generationen. `validate` räknar endast levande dokument och upptäcker inte den kvarvarande raden.
- En begränsad reindex måste bevara alla andra aktiva collectioner, medan en full reindex måste ge exakt samma dokumentmängd som SQLite. Båda fallen saknar nu ett test som även innehåller borttagna/stale indexrader.
- Child-process-händelserna `GENERATION_BUILT`, `BEFORE_MARKER_SWAP` och `AFTER_MARKER_SWAP` finns i WIP-testet, men testet kan inte kompileras och har därför inte bevisat markörens eller startup-svepets beteende.
- Underhållsgaten, `REINDEX`-jobbet och migration `003_job_types.sql` är inte verifierade tillsammans genom återstart, konkurrerande import/radering och återupptagning.
- Route-koden innehåller avsedda statusmappningar, men inget test bevisar obligatorisk collection, 4xx för `QUERY_TOO_LONG`/`FILTER_TOO_BROAD`/rewrite-rester, rätt gren för `MODEL_NOT_INSTALLED` respektive GPU-fel eller cross-collection-404 för content units.

## Reparationsordning

### 1. Frys WIP och skapa en kompilerbar testbas

1. Jämför `534fad3..HEAD` med Task 18-briefen och ändra inte produktbeteende bara för att passa ett trasigt test.
2. Rätta `ReindexRecoveryTest` mot publika API:n: använd `AppContext.index()`, `context.use {}`, riktiga `AppPaths`-importer och `runBlocking` runt suspend-anrop. Ta bort de manuella `componentN`-metoderna och förenkla fixture-returvärdet om destrukturering fortfarande skapar typfel.
3. Kör endast `compileTestKotlin` tills den är grön. Därefter körs de fokuserade reindex- och jobbtesten. Inga senare implementationer får döljas bakom ett okompilerat test.

### 2. Gör generationsinnehållet exakt

1. Bestäm en enda produktionsväg för en komplett generation: antingen bygg från tom generation eller ta bort alla dokument/collectioner som inte finns i den aktuella SQLite-snapshoten innan publicering.
2. Behåll optimeringen för ett smalt bygge bara om den bevarar alla andra collectioner och samtidigt tar bort dokument som har försvunnit. Lägg till en explicit live-ID-jämförelse per collection, inte bara radantal.
3. Validera efter reopen att varje aktiv collection och varje dokument har exakt förväntade content-unit/chunk-rader och att inga gamla dokument finns kvar. Valideringen ska omfatta full och begränsad reindex.
4. Bevara stabila unit-ID:n och extraction-checkpoints; reindex får uppdatera härledda chunks när tokenizer/metadata kräver det, men får inte skapa nya identiteter utan skäl.

### 3. Bevisa marker- och krasprotokollet

1. Reparera child-process-fixturen så den kör produktionswiring med fake embedder och kan stoppas vid varje `ReindexStep`.
2. Testa kill före commit, före markerbyte och efter markerbyte. Starta sedan ett nytt `AppContext` och kontrollera att `index/current` väljer exakt den generation som markören namnger.
3. Kontrollera att opublicerade `lucene-next-*`, gamla generationer och temporär markör städas vid startup, medan den markerade generationen aldrig tas bort.
4. Kontrollera att läsare som redan har leasat gamla generationen kan slutföra och att den gamla generationen först därefter stängs.

### 4. Bevisa exklusivt underhåll och jobbåterstart

1. Starta reindex med en blockerande fake embedder. Försök importera och radera collection under bygget och kontrollera 423/`MaintenanceInProgressException` enligt respektive boundary.
2. Släpp bygget och kontrollera att mutationer därefter accepteras och syns i den publicerade generationen.
3. Testa `REINDEX`-migrationen från en äldre schema-version, job claim, processavbrott, `resetInterrupted` och återupptagning. Verifiera att payloadens collection-ID återanvänds och att ett avbrutet bygge inte publiceras.
4. Kontrollera att endast en reindex kan publicera och att en stale publisher vägrar ersätta en nyare current-generation.

### 5. Lägg till boundary-tester för HTTP och CLI

1. Skapa route-tester för GET/POST search, reindex och content-unit. Kontrollera obligatorisk collection, aktiv collection-upplösning, cross-collection-404, inga fullständiga dokument i söksvaret och stabilt tomt JSON-svar.
2. Kontrollera 4xx för caller-fixbara fel och 503 för modell/GPU/index-miljöfel, med rätt felkod och utan känslig text i loggar eller fel.
3. Testa CLI i serverläge och foregroundläge: `search --collection ... --json ...` ska alltid ge giltig JSON även utan träffar; `reindex --wait --json` ska rapportera terminalt jobbresultat och hålla processägarskapet.

### 6. Gate, review och commit

Kör i denna ordning:

```text
JAVA_HOME=$(asdf where java) ./gradlew compileTestKotlin
JAVA_HOME=$(asdf where java) ./gradlew test --tests infoscry.search.ReindexRecoveryTest
JAVA_HOME=$(asdf where java) ./gradlew test --tests 'infoscry.jobs.*'
JAVA_HOME=$(asdf where java) ./gradlew check
JAVA_HOME=$(asdf where java) ./gradlew run --args='search --collection Default --json test'
git diff --check
```

Verifiera därefter diffen mot Task 18-briefens sex protokollpunkter och skriv ett kort review-underlag med testnamn och resultat. Committa först när testkod, produktionskod och dokumentation är gröna; behåll en avgränsad Task 18-commit och uppdatera `docs/implementation-status.md` med faktisk gate-status.

## Acceptanskriterier

- Testkällan kompilerar utan privata API-anrop eller typ-/suspend-fel.
- En reindex som avbryts före markerbyte lämnar gamla generationen aktiv; avbrott efter markerbyte återöppnar den nya.
- En publicerad generation innehåller exakt SQLite:s aktiva dokument, chunks och stabila unit-ID:n, inklusive borttagningar.
- Begränsad reindex bevarar övriga collectioner och full reindex verifierar alla collectioner.
- Import och collection-radering nekas under exklusivt underhåll och fungerar igen efteråt.
- HTTP- och CLI-kontrakten har körbara tester för collection-boundary, felstatusar, JSON och jobbägarskap.
- `./gradlew check` och CLI-smoketestet passerar; inga privata dokument eller riktiga externa modeller används i normala tester.

## Stop conditions

Arbetet ska stanna med en tydlig rapport om någon av följande kvarstår: testkompilering är röd, markerad generation kan raderas, stale dokument överlever en publicering, en mutation kan skriva under reindex, eller en route/CLI-felkod saknar ett verifierat test. Då ska inget Task 18 avslutningscommittas och status ska fortsatt ange partial/unverified.
