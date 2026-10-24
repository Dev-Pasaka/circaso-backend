# Shared by the port 80 and port 443 servers (included inside each `server` block).

server_tokens off;

# Only Cloudflare may talk to this server. Anyone who finds its address and connects directly is dropped without an answer. (The host firewall cannot
# do this: Docker publishes ports around it.)
if ($from_cloudflare = 0) { return 444; }

# Docker's own resolver, so a restarted app container is found again at its new address.
resolver 127.0.0.11 valid=10s ipv6=off;
set $upstream app:8080;

client_max_body_size 12m;
client_body_timeout 15s;
client_header_timeout 15s;
send_timeout 30s;
limit_conn perip 40;

add_header X-Content-Type-Options "nosniff" always;
add_header Referrer-Policy "no-referrer" always;
add_header Strict-Transport-Security "max-age=31536000" always;

# Our own error pages instead of nginx's, for errors nginx makes itself (a refused request, a rate limit); answers from the app, such
# as its JSON 404, pass through untouched. The designed pages live in the gateway (resources/errors) and nginx fetches them from the
# app at /_errors/<status>, telling it the original method and path; the 403 page shows the caller their own address, country,
# browser and request. The app cannot serve a page when it is down, so 502, 503 and 504 use one static file, nginx/errors/50x.html.
# If the app is down when a page is wanted (/_errors/... fails with 502), nginx must still apply error_page to that failure.
recursive_error_pages on;
limit_req_status 429;
limit_conn_status 429;
include /etc/nginx/conf.d/errors.inc;

location ^~ /_errors/ {
    internal;
    proxy_set_header X-Original-Method $request_method;
    proxy_set_header X-Original-URI $request_uri;
    include /etc/nginx/conf.d/proxy.inc;
}

location = /errors/50x.html {
    internal;
    root /etc/nginx/conf.d;
    default_type text/html;
    # A location that sets its own headers drops the ones above, so they are repeated here.
    add_header Cache-Control "no-store" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header Referrer-Policy "no-referrer" always;
    add_header Strict-Transport-Security "max-age=31536000" always;
    add_header Content-Security-Policy "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'" always;
}

# Not for the public: readiness reveals which dependencies are down, and Kandra's health route lists the database.
location = /health/ready { return 404; }
location ^~ /kandra/ { return 404; }
location ^~ /dev/ { return 404; }

# The API reference is for people Cloudflare Access has let in (dashboard: Zero Trust > Access > Applications, path /docs). Access puts its signed token in
# this header on every request it allows, so a request without it did not come through Access: refuse it here as well, in case Access is ever misconfigured.
# The reader is its own small service (the docs container: static files); only the spec, which the app builds from its routes, comes from the app.
location = /docs/openapi.json {
    if ($http_cf_access_jwt_assertion = "") { return 403; }
    limit_req zone=api burst=60 nodelay;
    include /etc/nginx/conf.d/proxy.inc;
}

location ^~ /docs {
    if ($http_cf_access_jwt_assertion = "") { return 403; }
    limit_req zone=api burst=60 nodelay;
    set $docs docs:8080;
    proxy_intercept_errors on;
    include /etc/nginx/conf.d/errors.inc;
    proxy_pass http://$docs;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_connect_timeout 5s;
    proxy_read_timeout 30s;
}

# The API keeps the app's own JSON answers (its 404 is the envelope clients parse). $api_accept is defined in accept.conf.
location ^~ /v1/ {
    proxy_set_header Accept $api_accept;
    limit_req zone=api burst=60 nodelay;
    include /etc/nginx/conf.d/proxy.inc;
}

# Everything else is for people with a browser: a path that does not exist gets the designed 404 page. The app only speaks JSON and
# answers a browser that accepts just HTML with a bare 406, so nginx says "anything is fine" to it; its JSON 404 is then swapped for the page.
location / {
    proxy_intercept_errors on;
    include /etc/nginx/conf.d/errors.inc;
    proxy_set_header Accept "*/*";
    limit_req zone=api burst=60 nodelay;
    include /etc/nginx/conf.d/proxy.inc;
}
