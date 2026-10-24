# Which page nginx shows for which error. Included in the server and again in every location that sets its own error_page
# (an error_page inside a location replaces all the ones it would inherit), so the list is written once.
error_page 403 /_errors/403;
error_page 404 /_errors/404;
error_page 413 /_errors/413;
error_page 429 /_errors/429;
error_page 502 503 504 /errors/50x.html;
