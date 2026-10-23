# console-web in live mode (not its mock mode): the production build of the console workspace
# (`npm run build --workspace console`, which leaves VITE_CONSOLE_MOCK unset), served by nginx with
# the routes the Vite dev server proxies (config/nginx/default.conf, mounted by the compose file).
# console-web has no Dockerfile. Build context: the checked-out console-web commit, after the build
# (scripts/build.sh).
FROM nginx:1.27-alpine
COPY console/dist /usr/share/nginx/html
EXPOSE 3000
