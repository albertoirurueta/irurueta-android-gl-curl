# Django REST Framework

Read this when the API style is DRF and the task touches a serializer, view/viewset, router, permission, throttle,
pagination, filter, or the OpenAPI schema. DRF 3.18 needs Django 5.2+ (a Django 4.2 project stays on 3.17) and is
**sync only** — no `async def` handlers (use a native async Django view, Ninja, or `adrf` only if the project already
does).

## Layout

API code lives where the project keeps it — `<app>/api/{serializers,views,urls}.py`, or a dedicated `apps/api` app
(no models). Mounted under a versioned prefix (`path("api/v1/", include("apps.catalog.api.urls"))`, or
`re_path(r"^api/(?P<version>v1|v2)/", …)` with `URLPathVersioning`). Follow the existing mount; never add an
unversioned public API next to a versioned one.

## Serializers

Verified (mypy strict with djangorestframework-stubs):

```python
class BookSerializer(serializers.ModelSerializer[Book]):
    """Read/write representation of a book; ``owner`` is set server-side."""

    author_name = serializers.CharField(source="author.name", read_only=True)

    class Meta:
        model = Book
        fields = ("id", "title", "author", "author_name", "status", "stock")
        read_only_fields = ("id", "stock")


class ReserveSerializer(serializers.Serializer[None]):
    """Input of the reserve action."""

    quantity = serializers.IntegerField(min_value=1, max_value=100)
```

- **Explicit `fields`**, never `"__all__"` or `exclude`. Server-controlled fields (`owner`, `stock`, flags) are
  read-only or absent and set in `perform_create`/the service. Passwords and write-only ids (`author_id`) are
  `write_only=True`.
- Field rules in `validate_<field>()`, cross-field in `validate()`; raise `serializers.ValidationError` with a field
  key. Serializers don't call `full_clean()` — DB constraints still apply, so catch `IntegrityError` (or check first
  inside the service's transaction) and turn it into a 400/409, never a 500.
- Nested reads: `source="author.name"` or a nested serializer **plus** the matching `select_related`/
  `prefetch_related` in the view's queryset.
- Separate input and output serializers when the shapes differ (an action's payload vs. the resource).

## Views and viewsets

```python
class BookViewSet(viewsets.ModelViewSet[Book]):
    """CRUD for books, scoped to what the requesting user may see."""

    serializer_class = BookSerializer

    def get_queryset(self) -> QuerySet[Book]:
        return Book.objects.visible_to(self.request.user).select_related("author")

    def perform_create(self, serializer: BaseSerializer[Book]) -> None:
        serializer.save(owner=self.request.user)

    @extend_schema(request=ReserveSerializer, responses=BookSerializer)
    @action(detail=True, methods=["post"])
    def reserve(self, request: Request, pk: str | None = None) -> Response:
        book = self.get_object()                 # applies the scoped queryset + object permissions
        payload = ReserveSerializer(data=request.data)
        payload.is_valid(raise_exception=True)
        try:
            book = services.reserve_copies(book_id=book.pk, quantity=payload.validated_data["quantity"])
        except services.OutOfStockError as exc:
            raise ValidationError({"quantity": "Not enough copies in stock."}) from exc
        return Response(BookSerializer(book).data, status=status.HTTP_200_OK)
```

- `ModelViewSet` + router for CRUD; generic views (`ListCreateAPIView`) for partial CRUD; `APIView`/`@api_view` for
  custom endpoints; `@action(detail=..., methods=[...])` for non-CRUD verbs.
- **Override `get_queryset()`, never mutate the class-level `queryset`**; scope it to `request.user` for private
  data (object permissions don't filter lists). Use `self.get_object()` in actions.
- Branch per `self.action` in `get_serializer_class()` / `get_permissions()` / `get_throttles()`.
- Views call services for anything beyond a plain save; translate domain exceptions to `ValidationError` (400),
  `PermissionDenied` (403), `NotFound` (404), or a 409 `Response` — never let them surface as 500.
- View/viewset docstrings become operation descriptions in the generated schema — write them for API consumers.

## Permissions, authentication, throttling

- The project's `DEFAULT_PERMISSION_CLASSES` is the floor; a new view that must be stricter sets
  `permission_classes` explicitly. A public endpoint (`AllowAny`) needs the task to say so.
- Owner checks in `has_object_permission()` **plus** a scoped `get_queryset()`. `DjangoModelPermissions` doesn't gate
  GET — pair it with scoping.
- Authentication classes order matters: token/JWT before Session, or anonymous clients get 403 without
  `WWW-Authenticate` instead of 401. Tokens only in the `Authorization` header, never in the query string.
- Throttle public and abuse-prone endpoints (`ScopedRateThrottle` with a `throttle_scope`); rates live in settings.

## Pagination, filtering, versioning

- Every list is paginated (project default `PageNumberPagination`; `CursorPagination` for feeds/large tables) over an
  **ordered** queryset.
- django-filter (`filterset_fields`/a `FilterSet`), `SearchFilter`, `OrderingFilter` with explicit `ordering_fields` —
  never ordering on arbitrary client-supplied fields.
- Version-dependent behavior branches on `request.version`; a retired version answers 410 Gone.

## OpenAPI (drf-spectacular)

`@extend_schema(request=…, responses=…)` on actions and `APIView`s whose shapes can't be inferred; `@extend_schema_field`
on `SerializerMethodField`s; guard `get_queryset()` code that needs a real user with
`if getattr(self, "swagger_fake_view", False): return Book.objects.none()`. The group validates the schema with
`manage.py spectacular --validate --fail-on-warn`; every warning (an un-annotated method field, an action without a
serializer) fails the bucket, so annotate as you write.

## Tests for this area

`APIClient` (pytest) / `APITestCase`; `force_authenticate(user=…)` for permission tests, a real token/JWT for the
auth path itself; `format="json"`; assert status **and** body **and** database state; cover 400 (field named), 401/403
(anonymous, wrong role), 404 (another user's object), and a `django_assert_num_queries` budget on list endpoints.
Clear the cache in setup when throttling is on (counters persist; `override_settings(REST_FRAMEWORK=…)` is unreliable
because DRF caches settings at import).
