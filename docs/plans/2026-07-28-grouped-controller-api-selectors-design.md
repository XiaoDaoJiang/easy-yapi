# Grouped Controller API Selectors Design

## Goal

Resolve manifest entries once per Controller and allow a manifest entry to
select every endpoint exported from that Controller.

## Selector syntax

Method selectors remain unchanged:

```text
com.acme.UserController#getUser
com.acme.UserController.getUser(java.lang.String)
```

Both supported separators accept `*` as an explicit whole-Controller selector:

```text
com.acme.UserController#*
com.acme.UserController.*
```

A bare qualified class name remains unsupported because it cannot be
distinguished from the Java-style `class.method` form without PSI lookup.

## Data model

Represent parsed entries with a sealed selector hierarchy:

- `ControllerSelector` contains the Controller name and source line.
- `ControllerMethodSelector` additionally contains the method name and optional
  parameter signature.

This prevents nullable method fields and makes whole-class behavior explicit.

## Resolution flow

1. Group parsed selectors by Controller name.
2. Resolve and recognize each `PsiClass` once in a short read action.
3. If a group contains `ControllerSelector`, it wins and method selectors in
   that group are ignored.
4. Otherwise resolve the group's method selectors against the single
   `PsiClass`.
5. Collect all resolved Controller classes and call `ApiScanner.scanClasses`
   once, preserving its existing concurrency and exporter setup.
6. Use `ApiEndpoint.sourceClass` to route scanned endpoints back to the
   resolved Controller group. Whole-class groups keep every routed endpoint;
   method groups keep only endpoints related to their resolved methods.

All built-in class exporters populate `ApiEndpoint.sourceClass` with the
scanned class, so a unified scan does not lose Controller ownership.

## Errors

Class lookup and Controller recognition failures retain source line numbers.
Invalid methods do not prevent valid Controller groups from producing
endpoints. A whole-class selector suppresses method lookup errors in the same
group because it already selects the complete Controller.

## Performance

Class lookup and Controller recognition scale with distinct Controllers rather
than manifest lines. Endpoint filtering is restricted to methods from the
endpoint's Controller group instead of comparing every endpoint with every
selector. Scanning cost remains proportional to the selected Controller
classes because current exporters operate at class scope.

## Verification

- Parser tests cover `#*` and `.*`.
- Resolver fixture tests cover whole-class export and wildcard precedence.
- Existing simple, signature-qualified, overloaded, and background-thread
  resolution tests remain green.
