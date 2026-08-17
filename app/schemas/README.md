# Room schema history

Schema export became mandatory at database version 11. `com.yokuli.anchorwatch.data.database.AppDatabase/11.json` is the committed baseline.

- Legacy v5→current and v7→current fixtures protect the existing anchor/sonar migration chains.
- A dedicated v10→v11 test verifies operational data preservation and the Incident Log table/indices.
- Every database version after v11 must commit its generated JSON and add a direct `N→N+1` migration test before merge.
- Never edit generated schema JSON by hand; change Room entities/migrations and regenerate it with Gradle.

Schema v1–v10 JSON was never exported by historical builds, so fabricating those files now would provide false assurance. Their real SQL fixtures remain in `Migration5To6Test` and are migrated through the production chain.
