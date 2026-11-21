# Forms and validation

Read this when the task adds or changes a `Form`, `ModelForm`, formset, validator, or a model's `clean()`. API input
is validated by DRF serializers (`drf.md`) or Ninja schemas (`ninja.md`) with the same principles.

## Where each rule goes

| Rule | Put it in |
|---|---|
| Type coercion of one value | The form field (`to_python()` for a custom field) |
| A reusable rule on one value | A validator in `validators=[...]` — works on form and model fields |
| A single-field form rule | `clean_<field>()` — must **return** the cleaned value |
| A cross-field rule | `Form.clean()` with `self.add_error("field", error)` |
| A data rule for every entry point (admin, API, commands) | `Model.clean()` + model field validators |
| A rule that must hold even under races / bulk writes | `Meta.constraints` (`models-and-orm.md`) — and translate `IntegrityError` |

## Forms

(`forms.ModelForm[Review]` needs `django_stubs_ext.monkeypatch()` in the settings to run — drop the subscript
otherwise; see `code-style.md`.)

```python
class ReviewForm(forms.ModelForm[Review]):
    class Meta:
        model = Review
        fields = ("rating", "body")                  # explicit — never "__all__" or exclude

    def __init__(self, *args: Any, book: Book, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)
        self.book = book

    def clean_rating(self) -> int:
        rating: int = self.cleaned_data["rating"]
        if not 1 <= rating <= 5:
            raise ValidationError(_("Rating must be 1–5."), code="rating_range")
        return rating

    def clean(self) -> dict[str, Any]:
        cleaned = super().clean()                    # keeps unique/constraint validation
        if self.book.status != Book.Status.PUBLISHED:
            raise ValidationError(_("Only published books can be reviewed."), code="unpublished")
        return cleaned
```

- **Explicit `Meta.fields`**, never `"__all__"` or `exclude` — a new model field would silently become editable (mass
  assignment). Server-side fields (`owner`, `reviewer`, `is_staff`, `featured`, prices) are never form fields; set them
  in the view (`form.instance.owner = request.user`) or after `save(commit=False)`.
- `save(commit=False)` → set server-side fields → `obj.save()` → **`form.save_m2m()`**.
- Overriding `ModelForm.clean()` **must call `super().clean()`**, or unique and constraint validation vanish. A
  constraint over a field missing from the form makes `is_valid()` true and `save()` raise `IntegrityError` — include
  the field `disabled=True`, or catch the error and `add_error()`.
- In `clean()`, read with `self.cleaned_data.get(...)` (failed fields are absent); after `add_error()` the field is
  removed from `cleaned_data`.
- Extra context (`user`, `book`) comes in through `__init__` keyword arguments (`get_form_kwargs()` in CBVs), never via
  globals or thread locals. Dynamic choices via a callable or `ModelChoiceField(queryset=...)` set in `__init__` —
  never a query in the class body. Give `ModelChoiceField` lean, **user-scoped** querysets.
- `BooleanField(required=False)` for optional checkboxes; `initial` is display-only; `disabled=True` for read-only
  values (hidden/readonly inputs are user input).
- Subclassed `Meta` inherits: `class Meta(BaseForm.Meta): …`. User forms subclass `BaseUserCreationForm`/
  `UserChangeForm` with their own `Meta.model` (`auth.md`).
- Search/filter forms use `method="get"` bound to `request.GET` — no CSRF, no redirect, no state change.

## Raising errors well

`ValidationError(_("Invalid value: %(value)s"), code="invalid", params={"value": value})` — a stable `code`, named
`params`, `gettext_lazy`/`gettext`, never an f-string. Several problems → a list or a dict keyed by field. Tests read
errors by code (`form.has_error("rating", "rating_range")`), not by translated text.

## Validators

Plain functions for fixed rules; `@deconstructible` classes with `__eq__` for configurable ones (migrations serialize
them). Built-ins first: `MinValueValidator`, `MaxValueValidator`, `RegexValidator`, `FileExtensionValidator`,
`validate_email`. Validators check shape — not authorization, and `FileExtensionValidator` is not a security control on
its own (`security.md`).

## Formsets

Render `{{ formset.management_form }}` and the hidden id/FK fields; unique `prefix` per formset on a page; set
`max_num` + `validate_max` and keep `absolute_max` low (don't trust a forged `TOTAL_FORMS`). **Scope model-formset
querysets to the user** — posted PKs outside the queryset are silently ignored, not rejected. With `commit=False`,
delete `formset.deleted_objects` yourself. `inlineformset_factory(Parent, Child, fields=[...])` for parent + children
on one page; `edit_only=True` to forbid new rows.

## Rendering

`{{ form }}` (div layout since 5.0) or per field `{{ form.title.as_field_group }}`; don't hand-write label/help/error
markup or drop `aria-describedby`/`aria-invalid`. Project-wide markup through `FORM_RENDERER = "…TemplatesSetting"`
and overridden `django/forms/*.html` — only if the project already does it. Never build `help_text` from user data
(the stock template marks it safe). Media through an inner `Media` class and `{{ form.media }}` once in the base
template. Form fields are the only two-way-bound controls in a Django UI (`ui-contract.md` §4): a form page or partial
is a component like any other — its `default`, `error` (bound with errors) and `disabled` states get browser UI tests
and screenshots (`ui-implementation.md` §5).

## Async

There is no `ais_valid()`. Pure-Python forms are fine in async views; ORM-touching validation runs as
`await sync_to_async(form.is_valid)()`.

## Tests for this area

Forms alone in `SimpleTestCase` / a plain pytest function when no model is touched — `@pytest.mark.parametrize` over
valid and invalid inputs, assert `form.has_error("field", "code")` or `assertFormError(form, "field", msg)` (5.0+
signature). Through the view: status (422/200 re-render), the error shown, nothing written to the database, and that
posted server-side fields (`owner`, `is_staff`) were ignored.
