#!/usr/bin/env python3
import contextlib
import os
import queue
import subprocess
import sys
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
from urllib.parse import urlparse

from spa_ripper.scraper import SpaScraper


class QueueWriter:
    def __init__(self, target_queue):
        self.target_queue = target_queue

    def write(self, text):
        if text:
            self.target_queue.put(("log", text))

    def flush(self):
        pass


class SpaRipperGUI:
    def __init__(self, root):
        self.root = root
        self.root.title("SPA-Ripper")
        self.root.geometry("860x620")
        self.root.minsize(720, 520)

        self.events = queue.Queue()
        self.worker = None
        self.last_output = None

        self.url_var = tk.StringVar(value="https://")
        self.output_var = tk.StringVar()
        self.status_var = tk.StringVar(value="Ready")

        container = ttk.Frame(root, padding=16)
        container.pack(fill="both", expand=True)

        ttk.Label(
            container,
            text="SPA-Ripper",
            font=("TkDefaultFont", 20, "bold"),
        ).pack(anchor="w")
        ttk.Label(
            container,
            text="Clone modern SPA frontends with recursive asset discovery.",
        ).pack(anchor="w", pady=(0, 16))

        ttk.Label(container, text="Website URL").pack(anchor="w")
        url_row = ttk.Frame(container)
        url_row.pack(fill="x", pady=(4, 12))
        self.url_entry = ttk.Entry(url_row, textvariable=self.url_var)
        self.url_entry.pack(side="left", fill="x", expand=True)
        self.url_entry.bind("<FocusOut>", self._suggest_output)

        ttk.Label(container, text="Output folder").pack(anchor="w")
        output_row = ttk.Frame(container)
        output_row.pack(fill="x", pady=(4, 12))
        ttk.Entry(output_row, textvariable=self.output_var).pack(
            side="left", fill="x", expand=True
        )
        ttk.Button(
            output_row,
            text="Browse",
            command=self.choose_output,
        ).pack(side="left", padx=(8, 0))

        actions = ttk.Frame(container)
        actions.pack(fill="x", pady=(0, 12))

        self.clone_button = ttk.Button(
            actions,
            text="Clone Frontend",
            command=self.start_clone,
        )
        self.clone_button.pack(side="left")

        self.open_button = ttk.Button(
            actions,
            text="Open Output",
            command=self.open_output,
            state="disabled",
        )
        self.open_button.pack(side="left", padx=(8, 0))

        self.progress = ttk.Progressbar(actions, mode="indeterminate")
        self.progress.pack(side="right", fill="x", expand=True, padx=(16, 0))

        ttk.Label(container, textvariable=self.status_var).pack(anchor="w")

        log_frame = ttk.LabelFrame(container, text="Clone log", padding=8)
        log_frame.pack(fill="both", expand=True, pady=(8, 0))

        self.log = tk.Text(
            log_frame,
            wrap="word",
            state="disabled",
            font=("Consolas", 10),
        )
        scrollbar = ttk.Scrollbar(
            log_frame,
            orient="vertical",
            command=self.log.yview,
        )
        self.log.configure(yscrollcommand=scrollbar.set)
        self.log.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")

        self.root.after(100, self.process_events)

    def _suggest_output(self, _event=None):
        if self.output_var.get().strip():
            return
        raw = self.url_var.get().strip()
        if raw and not raw.startswith(("http://", "https://")):
            raw = "https://" + raw
        host = urlparse(raw).netloc.split(":")[0]
        if host:
            safe = host.replace(".", "_")
            self.output_var.set(
                os.path.abspath(os.path.join("downloads", safe + "_frontend"))
            )

    def choose_output(self):
        selected = filedialog.askdirectory(title="Choose output folder")
        if selected:
            self.output_var.set(selected)

    def start_clone(self):
        if self.worker and self.worker.is_alive():
            return

        raw_url = self.url_var.get().strip()
        if not raw_url:
            messagebox.showerror("SPA-Ripper", "Enter a website URL.")
            return

        if not raw_url.startswith(("http://", "https://")):
            raw_url = "https://" + raw_url
            self.url_var.set(raw_url)

        self._suggest_output()
        output = self.output_var.get().strip()
        if not output:
            messagebox.showerror("SPA-Ripper", "Choose an output folder.")
            return

        self._set_log("")
        self.clone_button.configure(state="disabled")
        self.open_button.configure(state="disabled")
        self.progress.start(12)
        self.status_var.set("Cloning…")

        self.worker = threading.Thread(
            target=self._clone_worker,
            args=(raw_url, output),
            daemon=True,
        )
        self.worker.start()

    def _clone_worker(self, url, output):
        writer = QueueWriter(self.events)
        try:
            os.makedirs(output, exist_ok=True)
            scraper = SpaScraper(
                base_url=url,
                output_dir=output,
                timeout=20,
            )
            with contextlib.redirect_stdout(writer), contextlib.redirect_stderr(writer):
                scraper.run()

            index_path = os.path.join(output, "index.html")
            if not os.path.exists(index_path):
                raise RuntimeError("Clone did not produce index.html.")

            self.events.put(
                (
                    "done",
                    {
                        "output": os.path.abspath(output),
                        "files": scraper.processed_count,
                        "failed": len(scraper.failed_urls),
                    },
                )
            )
        except Exception as exc:
            self.events.put(("error", str(exc)))

    def process_events(self):
        try:
            while True:
                kind, payload = self.events.get_nowait()
                if kind == "log":
                    self._append_log(payload)
                elif kind == "done":
                    self.progress.stop()
                    self.clone_button.configure(state="normal")
                    self.open_button.configure(state="normal")
                    self.last_output = payload["output"]
                    self.status_var.set(
                        f'Done — {payload["files"]} files, '
                        f'{payload["failed"]} failed. '
                        f'Output: {payload["output"]}'
                    )
                    self._append_log(
                        "\n[✓] Clone complete.\n"
                        f'[✓] Output folder: {payload["output"]}\n'
                    )
                elif kind == "error":
                    self.progress.stop()
                    self.clone_button.configure(state="normal")
                    self.status_var.set("Clone failed")
                    self._append_log(f"\n[!] {payload}\n")
                    messagebox.showerror("SPA-Ripper", payload)
        except queue.Empty:
            pass
        self.root.after(100, self.process_events)

    def _set_log(self, text):
        self.log.configure(state="normal")
        self.log.delete("1.0", "end")
        if text:
            self.log.insert("end", text)
        self.log.configure(state="disabled")

    def _append_log(self, text):
        self.log.configure(state="normal")
        self.log.insert("end", text)
        self.log.see("end")
        self.log.configure(state="disabled")

    def open_output(self):
        if not self.last_output or not os.path.isdir(self.last_output):
            return
        try:
            if sys.platform.startswith("win"):
                os.startfile(self.last_output)
            elif sys.platform == "darwin":
                subprocess.Popen(["open", self.last_output])
            else:
                subprocess.Popen(["xdg-open", self.last_output])
        except Exception as exc:
            messagebox.showinfo(
                "Output folder",
                f"{self.last_output}\n\nCould not open automatically: {exc}",
            )


def main():
    root = tk.Tk()
    SpaRipperGUI(root)
    root.mainloop()


if __name__ == "__main__":
    main()
