# The web app: the static build behind nginx, which proxies /api, /ws and the backend's health and
# info to API_UPSTREAM, so the browser talks to one origin. Build from the repository root (the app
# uses the workspaces under packages/):
#   docker build -f frontend/Dockerfile -t platform-frontend .
FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
COPY packages/ui/package.json packages/ui/
COPY packages/workflow-checks/package.json packages/workflow-checks/
COPY frontend/package.json frontend/
RUN npm ci --no-audit --no-fund
COPY packages ./packages
COPY frontend ./frontend
RUN npm run build --workspace frontend

# runs as nginx (uid 101) and listens on 8080
FROM nginxinc/nginx-unprivileged:1.29-alpine
ENV API_UPSTREAM=http://backend:8080
COPY frontend/nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /src/frontend/dist /usr/share/nginx/html
EXPOSE 8080
