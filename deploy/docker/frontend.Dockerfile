FROM node:22-alpine AS build

WORKDIR /workspace
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend ./
ARG VITE_APP_VERSION="0.1.0"
ARG VITE_APP_REVISION="dokploy"
ENV VITE_APP_VERSION=$VITE_APP_VERSION
ENV VITE_APP_REVISION=$VITE_APP_REVISION
RUN npm run build

FROM nginx:1.28-alpine

COPY deploy/docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://127.0.0.1:8080/healthz || exit 1
