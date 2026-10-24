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

# Not for the public: readiness reveals which dependencies are down, and Kandra's health route lists the database.
location = /health/ready { return 404; }
location ^~ /kandra/ { return 404; }
location ^~ /dev/ { return 404; }

# The API reference is for people Cloudflare Access has let in (dashboard: Zero Trust > Access > Applications, path /docs). Access puts its signed token in
# this header on every request it allows, so a request without it did not come through Access: refuse it here as well, in case Access is ever misconfigured.
location ^~ /docs {
    if ($http_cf_access_jwt_assertion = "") { return 403; }
    limit_req zone=api burst=60 nodelay;
    include /etc/nginx/conf.d/proxy.inc;
}

location / {
    limit_req zone=api burst=60 nodelay;
    include /etc/nginx/conf.d/proxy.inc;
}
