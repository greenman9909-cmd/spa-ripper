const http = require("http");
const fs = require("fs");
const path = require("path");

const root = __dirname;
const port = Number(process.env.PORT || 3000);
const mime = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".webp": "image/webp",
  ".ico": "image/x-icon"
};

function safePath(urlPath) {
  let clean;
  try {
    clean = decodeURIComponent((urlPath || "/").split("?")[0]);
  } catch {
    return null;
  }

  const requested = clean === "/" ? "/index.html" : clean;
  const target = path.resolve(root, "." + requested);
  return target === root || target.startsWith(root + path.sep) ? target : null;
}

http.createServer((req, res) => {
  let file = safePath(req.url);
  if (!file) {
    res.writeHead(400);
    return res.end("Bad request");
  }

  fs.stat(file, (err, stat) => {
    if (err || !stat.isFile()) file = path.join(root, "index.html");

    fs.readFile(file, (readErr, data) => {
      if (readErr) {
        res.writeHead(500);
        return res.end("Server error");
      }

      const type = mime[path.extname(file).toLowerCase()] || "application/octet-stream";
      res.writeHead(200, {
        "Content-Type": type,
        "Cache-Control": type.startsWith("text/html") ? "no-cache" : "public, max-age=3600"
      });
      res.end(data);
    });
  });
}).listen(port, "0.0.0.0", () => {
  console.log("Embercrawl listening on " + port);
});