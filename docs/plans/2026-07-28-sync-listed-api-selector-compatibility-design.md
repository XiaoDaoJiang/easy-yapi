# Sync Listed API Selector Compatibility Design

## Goal

Allow `.easyapi/sync-apis.txt` to use Java-style `class.method` selectors in
addition to the existing `class#method` selectors, and fix the read-access
violation raised while resolving listed methods on a background thread.

## Accepted formats

Both separators support an optional fully qualified parameter list:

```text
com.gyenno.scoring.project.api.PatientApi#queryPatientList
com.gyenno.scoring.project.api.PatientApi#queryPatientList(java.lang.String)
com.gyenno.scoring.project.api.PatientApi.queryPatientList
com.gyenno.scoring.project.api.PatientApi.queryPatientList(java.lang.String)
```

The parser keeps producing the existing `ControllerMethodSelector`; endpoint
resolution and export channels remain unchanged. `::` and JVM descriptors are
not supported because they add formats without improving the current workflow.

For dot syntax, the separator is the final dot before the optional opening
parenthesis. This avoids mistaking dots inside parameter type names for the
class/method separator. A bare qualified name is syntactically indistinguishable
from dot syntax and will therefore be reported later as an unresolved class or
method.

## Read-action fix

`ListedApiEndpointResolver.resolve` currently reads
`PsiMethod.containingClass` after leaving `resolveMethods`' read action. Move
only the containing-class extraction into `read {}`. Scanning remains outside
the read action, and `areMethodsRelated` continues to manage its own read
action.

## Verification

- Plain JUnit parser tests cover simple and signature-qualified dot selectors,
  while retaining hash-selector coverage.
- The PSI fixture resolver test invokes resolution on
  `IdeDispatchers.Background`; it must complete without a read-access exception.
- Run the parser and resolver test classes together.
