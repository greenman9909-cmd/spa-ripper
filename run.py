#!/usr/bin/env python3
import sys


def main():
    if len(sys.argv) == 1 or sys.argv[1] == "--gui":
        from gui import main as gui_main
        gui_main()
        return

    from spa_ripper.cli import main as cli_main
    cli_main()


if __name__ == "__main__":
    main()
