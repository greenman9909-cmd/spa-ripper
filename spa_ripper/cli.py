import argparse
import sys
from spa_ripper.scraper import SpaScraper
from spa_ripper.server import SpaDevServer


def main():
    parser = argparse.ArgumentParser(
        prog="spa-ripper",
        description="Clone modern Single Page Application (SPA) frontends with recursive dynamic chunk discovery and serve them locally.",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    # Clone command
    clone_parser = subparsers.add_parser("clone", help="Clone a frontend from a target URL")
    clone_parser.add_argument("url", help="Target URL (e.g. https://ani.pm/)")
    clone_parser.add_argument(
        "-o", "--output", default="frontend", help="Output directory to save assets (default: frontend)"
    )
    clone_parser.add_argument(
        "-t", "--timeout", type=int, default=15, help="Request timeout in seconds (default: 15)"
    )

    # Serve command
    serve_parser = subparsers.add_parser("serve", help="Serve a cloned frontend locally")
    serve_parser.add_argument(
        "directory", nargs="?", default="frontend", help="Directory containing the cloned frontend (default: frontend)"
    )
    serve_parser.add_argument(
        "-p", "--port", type=int, default=8080, help="Port to listen on (default: 8080)"
    )
    serve_parser.add_argument(
        "--host", default="0.0.0.0", help="Host interface (default: 0.0.0.0)"
    )
    serve_parser.add_argument(
        "--proxy", default=None, help="Backend origin to proxy /api/* requests to (e.g. http://127.0.0.1:8000 or https://ani.pm)"
    )
    serve_parser.add_argument(
        "--api-prefix", default="/api/", help="API route prefix for proxying (default: /api/)"
    )

    args = parser.parse_args()

    if args.command == "clone":
        scraper = SpaScraper(
            base_url=args.url,
            output_dir=args.output,
            timeout=args.timeout,
        )
        scraper.run()

    elif args.command == "serve":
        server = SpaDevServer(
            directory=args.directory,
            port=args.port,
            host=args.host,
            proxy_target=args.proxy,
            api_prefix=args.api_prefix,
        )
        server.start()


if __name__ == "__main__":
    main()
