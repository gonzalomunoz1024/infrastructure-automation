# The Kafka half of the platform's event feed (P-01) for platform-fake, which serves the feed over
# HTTP only. Build context: this directory, after `npm ci --omit=dev` (scripts/build.sh).
FROM node:22-alpine
WORKDIR /app
COPY package.json package-lock.json bridge.mjs ./
COPY node_modules ./node_modules
USER node
ENTRYPOINT ["node", "bridge.mjs"]
