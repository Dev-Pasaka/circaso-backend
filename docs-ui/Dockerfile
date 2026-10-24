# syntax=docker/dockerfile:1
# The API reference as its own small image: static files behind nginx, no JVM. Build the files first (the deploy workflow does):
#   cd docs-ui && bun run build.ts
FROM nginxinc/nginx-unprivileged:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY dist/docs/ /usr/share/nginx/html/docs/
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --retries=4 CMD wget -q -O /dev/null http://127.0.0.1:8080/health || exit 1
