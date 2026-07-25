"""Render an HTML file to PDF using the pre-installed Chromium via Playwright.

nbconvert's own webpdf exporter fails to auto-locate the pre-installed browser
in this environment, so we drive Playwright directly with an explicit
executable_path instead.
"""
import sys

from playwright.sync_api import sync_playwright

CHROMIUM_PATH = "/opt/pw-browsers/chromium-1194/chrome-linux/chrome"


def html_to_pdf(html_path: str, pdf_path: str, landscape: bool = False):
    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path=CHROMIUM_PATH)
        page = browser.new_page()
        page.goto(f"file://{html_path}")
        page.pdf(path=pdf_path, print_background=True, landscape=landscape,
                 prefer_css_page_size=True)
        browser.close()
    print(f"Wrote {pdf_path}")


if __name__ == "__main__":
    html_to_pdf(sys.argv[1], sys.argv[2])
