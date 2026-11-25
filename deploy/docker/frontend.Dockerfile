FROM node:22-alpine@sha256:1314fb30ec37592d5845e5a160d522c0e680972aa534549940b8d73bde88481a AS build

WORKDIR /workspace
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend ./
ARG VITE_APP_VERSION="0.1.0"
ARG VITE_APP_REVISION
ENV VITE_APP_VERSION=$VITE_APP_VERSION
ENV VITE_APP_REVISION=$VITE_APP_REVISION
RUN node --input-type=module -e 'if (!/^[a-f0-9]{40}$/i.test(process.env.VITE_APP_REVISION || "")) throw new Error("VITE_APP_REVISION exige SHA completo fornecido externamente")' && npm run build

FROM nginx:1.30.8-alpine@sha256:6437a63b5c529f0e06162b3f593c0154cff6fa2ea0f94fd7c27928cb399e022d

ARG VITE_APP_REVISION
LABEL org.opencontainers.image.revision=$VITE_APP_REVISION

COPY deploy/docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://127.0.0.1:8080/healthz || exit 1
