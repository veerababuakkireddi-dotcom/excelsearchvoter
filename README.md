# Voter Search (Gopalapatnam)

Offline Android voter search. It uses the Excel voter list (no PDF, no OCR, no internet).

- The list from `Gopalapatnam_Voter_List_2026.xlsx` ("All voters" sheet, 2,426 voters) is bundled as `app/src/main/assets/voters.csv` and loaded on first launch.
- Search by English or Telugu name, relative's name, voter ID, house number or serial. Every word you type must match, e.g. `ramana pilli`.
- Filter by ward with the dropdown.
- "Duplicate names table": voters sharing a name (235 voters in 108 groups), sortable and filterable by every column.
- "Import / replace list" accepts a new .xlsx or .csv with the same column headers.
- English names are phonetic spellings, so also try variants (e.g. "Appa Rao" / "Apparao"), or search the Telugu name or voter ID.

Build: open in Android Studio and run Build > Build APK(s), or use the GitHub Actions workflow (artifact `VoterSearch-debug-apk`). Commit the `app/src/main/assets` folder too.
