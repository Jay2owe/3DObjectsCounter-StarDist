# Versioning

Same policy as the sibling plugins, so a version number means the same thing
across the family.

`MAJOR.MINOR.PATCH`

| Digit | Increment when |
|---|---|
| **MAJOR** | A new feature set arrives — a new command, a new engine, a new output family |
| **MINOR** | A major rework or a large optimisation of what is already there |
| **PATCH** | A bug fix, or a small tweak or optimisation |

Column names, macro option names and the public Java API are treated as the
contract. Removing or renaming any of them is a MAJOR change even when the code
change is small, because a macro or a script somewhere depends on it.

Two things that are *not* versioning decisions but look like them:

- **Numbers changing because a defect was fixed** is a PATCH, and it goes in the
  changelog under Fixed with an explanation of what was wrong before. Users who
  have published results need to be able to tell whether a fix affects them.
- **A different TrackMate or StarDist version on the update sites** can change
  results without any change here at all. Record the versions tested against in
  the release notes.
