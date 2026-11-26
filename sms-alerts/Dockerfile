FROM node:24-bookworm-slim
WORKDIR /app
RUN mkdir /app/data && chown node:node /app/data
COPY --chown=node:node package.json server.js alerts.js bitunix.js melipayamak.js ./
USER node
ENV HOST=0.0.0.0
EXPOSE 8080
CMD ["node", "server.js"]
