FROM node:20-alpine AS build
WORKDIR /app

COPY package*.json ./
RUN npm ci

COPY . .
RUN npm run build

FROM nginx:1.27-alpine
WORKDIR /usr/share/nginx/html

COPY nginx.conf /etc/nginx/conf.d/default.conf
RUN rm -rf ./*
COPY --from=build /app/dist/video-chat-angular/browser/ ./

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
