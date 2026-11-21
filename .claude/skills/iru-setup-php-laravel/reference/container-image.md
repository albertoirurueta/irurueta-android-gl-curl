# Production container image (Step 12, `distribution: internal`)

One release, built once in CI and run unchanged everywhere; configuration and secrets (`APP_KEY`, `DB_*`,
`APP_VERSION`, …) come from the environment at run time, never from a `.env` baked into the image. Multi-stage:
Composer and Node only exist in build stages. Follows the irurueta docs pages `web/php-laravel/docker-and-sail.adoc`
and `web/php-laravel/deployment.adoc` (FrankenPHP as the documented container runtime; Nginx + PHP-FPM as the
classic alternative).

Both variants were built and run (2026-10-13, Laravel 13.36, PHP 8.4.26) against the scaffold this skill
produces: `/up` → 200, `/` → 200 with the Vite-built CSS served, `/.env` → 403 (Nginx variant),
`config('app.version')` picked up `APP_VERSION` from the container environment, Docker `HEALTHCHECK` → healthy.
Sizes: FrankenPHP image 629 MB (the base carries the extension build toolchain — the docs page observes the same);
FPM 115 MB + Nginx 63 MB.

Replace `<php-version>` with the resolved floor (major.minor, e.g. `8.4`). Add the database extension the app uses
to the `install-php-extensions` line: `pdo_mysql` (MySQL/MariaDB), `pdo_pgsql` (PostgreSQL); SQLite's `pdo_sqlite`
is already built in. Add `redis`, `intl`, `gd` only when the app needs them — each one adds build time and size.

## `.dockerignore` (both variants)

```
.git
.github
.env
.env.*
!.env.example
node_modules
vendor
build
tests
public/build
public/hot
public/storage
bootstrap/cache/*.php
storage/logs/*
storage/framework/cache/data/*
storage/framework/sessions/*
storage/framework/testing/*
storage/framework/views/*
database/*.sqlite
.phpunit.cache
compose.yaml
Dockerfile
```

`bootstrap/cache/*.php` matters: a `packages.php`/`services.php` generated on a development machine lists
dev-only providers (Pail, Boost, Sail) that `composer install --no-dev` doesn't install — the container would fail
to boot. Compiled views from a dev machine reference its absolute paths (the deployment page hit exactly that).

## Variant `frankenphp` (Recommended) — `Dockerfile`

```dockerfile
# syntax=docker/dockerfile:1
# Production image: one release, built once, configured at run time through environment variables
# (APP_KEY, DB_*, ...) - never through a baked-in .env.

ARG PHP_VERSION=<php-version>

FROM composer:2 AS vendor
WORKDIR /app
COPY composer.json composer.lock ./
RUN composer install --no-dev --no-scripts --no-autoloader --prefer-dist --no-interaction
COPY . .
RUN composer dump-autoload --no-dev --optimize --classmap-authoritative

FROM node:24-alpine AS assets
WORKDIR /app
COPY package.json package-lock.json .npmrc vite.config.js ./
RUN npm ci
COPY resources resources
RUN npm run build

FROM dunglas/frankenphp:1-php${PHP_VERSION} AS app
RUN install-php-extensions opcache pcntl
ENV APP_ENV=production APP_DEBUG=false LOG_CHANNEL=stderr SERVER_NAME=:8080
WORKDIR /app
COPY --chown=www-data:www-data --from=vendor /app /app
COPY --chown=www-data:www-data --from=assets /app/public/build /app/public/build
RUN cp "$PHP_INI_DIR/php.ini-production" "$PHP_INI_DIR/php.ini" \
    && chown -R www-data:www-data /data/caddy /config/caddy
USER www-data
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s CMD curl -fsS http://127.0.0.1:8080/up || exit 1
CMD ["sh", "-c", "php artisan optimize && exec frankenphp php-server --root public --listen :8080"]
```

Notes, all observed:

- `composer:2` runs a newer PHP than the floor (8.5 at verification time); that's fine because
  `config.platform.php` in `composer.json` pins dependency resolution to the floor.
- `composer dump-autoload --no-dev` runs `post-autoload-dump` → `artisan package:discover` inside the build stage,
  which regenerates `bootstrap/cache/packages.php` for production packages only.
- `npm ci` needs `package-lock.json`; the skeleton doesn't ship one, so Step 12 runs `npm install` once to create it
  (and it gets committed). The skeleton's `.npmrc` (`ignore-scripts=true`) is copied so the build honors it — drop it
  from the `COPY` line if the repository has no `.npmrc`.
- `php artisan optimize` runs at container start, not at build time: a config cache built in the image would freeze
  build-time environment values.
- Running as `www-data` needs `/data/caddy` and `/config/caddy` writable. `php artisan tinker` inside the container
  needs `-e HOME=/tmp` (no writable home directory — the docs page hit the same).
- With `trustHosts(...)` configured, the health check must send the real `Host` header (`curl -H 'Host: <host>'`),
  otherwise `/up` answers 400.

## Variant `fpm` — `Dockerfile` (two targets) + `docker/nginx/default.conf`

Same `vendor` and `assets` stages as above, then:

```dockerfile
FROM php:${PHP_VERSION}-fpm-alpine AS app
COPY --from=mlocati/php-extension-installer:2 /usr/bin/install-php-extensions /usr/local/bin/
RUN install-php-extensions opcache pcntl
ENV APP_ENV=production APP_DEBUG=false LOG_CHANNEL=stderr
WORKDIR /app
COPY --chown=www-data:www-data --from=vendor /app /app
COPY --chown=www-data:www-data --from=assets /app/public/build /app/public/build
RUN cp "$PHP_INI_DIR/php.ini-production" "$PHP_INI_DIR/php.ini" \
    && printf '[www]\nclear_env = no\n' > /usr/local/etc/php-fpm.d/zz-env.conf
USER www-data
EXPOSE 9000
CMD ["sh", "-c", "php artisan optimize && exec php-fpm"]

FROM nginx:1-alpine AS web
COPY docker/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=vendor /app/public /app/public
COPY --from=assets /app/public/build /app/public/build
EXPOSE 8080
```

Build with `docker build --target app -t <name>:<tag> .` and `docker build --target web -t <name>-web:<tag> .`.
`clear_env = no` lets the container's environment reach FPM workers (FPM clears it by default — the alternative is
one quoted `env[NAME] = "value"` line per variable; an unquoted `false` there stops FPM from starting).

`docker/nginx/default.conf` — the Laravel documentation's server block, with the FPM container's address and
`$realpath_root`:

```nginx
server {
    listen 8080;
    root /app/public;

    add_header X-Frame-Options "SAMEORIGIN";
    index index.php;
    charset utf-8;

    location / {
        try_files $uri $uri/ /index.php?$query_string;
    }

    location = /favicon.ico { access_log off; log_not_found off; }
    location = /robots.txt  { access_log off; log_not_found off; }

    error_page 404 /index.php;

    location ~ ^/index\.php(/|$) {
        fastcgi_pass app:9000;
        fastcgi_param SCRIPT_FILENAME $realpath_root$fastcgi_script_name;
        include fastcgi_params;
        fastcgi_hide_header X-Powered-By;
    }

    location ~ /\.(?!well-known).* {
        deny all;
    }
}
```

`app` is the FPM container's host name on the shared network (a Compose service name or `--network-alias app`).

## Smoke test (Step 15, when Docker is running)

```bash
docker build -t <slug>:verify .                      # fpm: add --target app, and build --target web too
KEY="base64:$(head -c 32 /dev/urandom | base64)"
docker run -d --name <slug>-verify -e APP_KEY="$KEY" -e SESSION_DRIVER=cookie -e CACHE_STORE=array \
  -p 127.0.0.1:<free-port>:8080 <slug>:verify
curl -fsS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:<free-port>/up      # expect 200
docker rm -f <slug>-verify && docker rmi <slug>:verify
```

`SESSION_DRIVER`/`CACHE_STORE` override the skeleton's `database` defaults so `/` renders without a migrated
database; production sets real values. Pick a free port — a busy one fails `docker run` with "address already in
use".
