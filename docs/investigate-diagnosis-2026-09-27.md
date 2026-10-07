# Investigate: felsökning 2026-09-27

> Uppföljning: denna diagnos är historisk. Implementationsstatus och
> verifieringsbevis finns i
> docs/tickets/investigate-follow-up-and-efficiency/STATUS.md.
> Fynden 1–5 är åtgärdade; browseracceptansen körs nu av `./gradlew externalTest`.

Scope: aktuell implementation av Investigate, inklusive befintliga lokala ändringar i service, routes, verktyg, reader och tester. Ingen programkod ändrades. README, historikspecifikationen och planen för forskningsgränser lästes.

## Följdfrågor och det rapporterade stoppet

Den lokala databasen lästes utan ändringar, med SQLite `mode=ro&immutable=1`; ingen server körde och ingen runtime-fil fanns. Den enda sparade Investigate-konversationen hade tre användarfrågor, 15 modellanrop och nio evidensposter. Efter den första frågans verktygsutbyten fanns två följdfrågor, vardera med ett ursprungligt och ett korrigerat assistantsvar. Modellanropen var SUCCEEDED. Samtliga fyra anrop för följdfrågorna hade noll poster i request_eligibility. Frågor, svar och källtext återges inte här.

Detta visar att följdfrågorna nådde backend och genererade svar i den sparade körningen. Det visar inte att webbläsaren visade svaren eller använde dagens frontend. Ingen aktiv InfoScry-server fanns att reproducera det upplevda stoppet mot. Ingen extern modell kontaktades under undersökningen.

En tidigare frontendbugg använde `event.evidence.filter` trots att servern utelämnar tom evidence i Done. Den kunde kasta ett JavaScript-fel under slutmeddelandet. Nuvarande InvestigatePanel använder `(event.evidence ?? []).filter`, och båda lokala jar-artefakterna innehåller skyddet. Den gamla buggen är därför en möjlig historisk förklaring, inte en reproducerad kvarvarande bugg i aktuell kod.

## Allvarliga fynd

1. **Följdfrågans gamla evidens saknas i citeringsvalideringen.** InvestigationService.kt:204 börjar med tom evidenceEntries; historiken återställer bara evidens-ID:n och meddelanden. Gamla tool-resultat skickas ändå med till modellen. Vid normal finalisering används bara evidenceEntries från den nya frågan (rader 732–745). Modellen kan därför använda en källa den faktiskt fått men få dess hänvisning underkänd. Den sparade körningens fyra följdfrågeanrop med noll tillåtna källor stöder detta. Korrigeringen får då tom tillåten ID-lista och tomt evidensunderlag. Följdfrågor riskerar att förlora källhänvisningar eller behöva söka och läsa samma material igen. Återställ komplett historisk evidens och koppla den till historikgrupper så att bara källor som faktiskt överlever pruning är tillåtna. Ett regressionstest måste täcka följdfråga som citerar en gammal källa utan nya verktyg och samma scenario efter pruning.

2. **Både det ursprungliga och korrigerade svaret blir vanlig konversationshistorik.** Ursprungligt assistantsvar läggs till i messageRecords före finalizeAnswer; en lyckad korrigering lägger till ännu ett på rad 415. Historikendpointen väljer alla assistantsvar med icke-tom text (InvestigationRoutes.kt:142). Den lokala databasen hade två assistantsvar efter var och en av följdfrågorna. Efter återöppning visas därför även det svar som ersattes, och senare frågor skickar båda tillbaka till modellen. Bevara originalet som auditdata men exponera bara det slutliga svaret som konversationssvar.

3. **120-sekundersgränsen är en total anropstid, inte inaktivitet.** InvestigationTimeouts.kt:37 använder withTimeout runt hela flödet. En modell som hela tiden producerar text eller verktygsargument kan ändå avbrytas efter 120 sekunder, även med 600 sekunders frågebudget kvar. Felet beskrivs som att modellen inte producerade en händelse i tid. Om avsikten är inaktivitet behövs en timer som återställs vid providerhändelser, parallellt med frågans absoluta deadline. Detta är belagt genom kod, inte genom ett 120 sekunders liveprov.

4. **Tester och nya standardgränser är inte samordnade.** Standarderna är nu 50 rundor och 50 anrop. Flera testfall bygger fortfarande på att 20 eller 21 anrop når standardgränsen, och route-testet bygger på tio rundor. Andra rundgränstest genererar nu 50 stora utbyten med en profil på 10 000 kontexttoken. Fokuserad backendkörning gav 65 tester, nio fel: sju i InvestigationServiceTest och två i InvestigationRoutesTest. Därmed är tidigare gränsverifiering inte längre giltig. Tester för en viss gräns bör sätta den explicit; standardvärden bör testas separat. De nio felen är inte bevis för nio produktionsbuggar.

## Mindre fynd

5. **Upprepade verktygsanrop jämförs som rå JSON-text.** InvestigationService.kt:782 bygger nyckeln av verktygsnamn och tc.arguments. Semantiskt identiska argument med annan nyckelordning eller whitespace räknas som nya anrop och kan fortsätta förbruka budget på samma sökning. Normalisera parsade argument före jämförelsen; behåll ändå legitima anrop med faktiskt ändrade argument.

## Verifiering och nästa steg

- Frontend: `npm test -- --run src/lib/InvestigatePanel.test.ts src/lib/api.test.ts src/routes/page.test.ts`: 76/76 passerade.
- Backend: `./gradlew test --tests 'infoscry.investigate.InvestigationServiceTest' --tests 'infoscry.server.InvestigationRoutesTest'`: 56/65 passerade, nio fel. Körningen avslutades normalt med BUILD FAILED.
- Mockade frontendtester bevisar inte verklig browseracceptans. Ingen livekörning med en extern provider gjordes.
- Ingen server startades eller startades om. Befintliga programändringar bevarades.

Prioritera fynd 1 och 2 samt en sammanhängande browserreproduktion med lokal fake provider: första fråga med evidens, följdfråga utan nya verktyg, återöppning och ytterligare följdfråga. Samordna gränstesterna enligt fynd 4 före godkännande av implementationen. Utred därefter timeoutsemantik och upprepningsnyckel.
