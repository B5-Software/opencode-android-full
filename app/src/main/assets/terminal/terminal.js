/* SPDX-License-Identifier: GPL-3.0-or-later */
"use strict";
const terminal = new Terminal({
  cursorBlink: true, fontSize: 14, scrollback: 2500,
  fontFamily: "monospace", allowProposedApi: false,
  theme: {background: "#0b100d", foreground: "#d5e5dc", cursor: "#9fddbe"}
});
const fit = new FitAddon.FitAddon();
terminal.loadAddon(fit);
terminal.open(document.getElementById("terminal"));
function encode(bytes) {
  let value = "";
  for (let i = 0; i < bytes.length; i += 8192) value += String.fromCharCode(...bytes.subarray(i, i + 8192));
  return btoa(value);
}
terminal.onData(value => {
  if (!TerminalIO.write(encode(new TextEncoder().encode(value)))) terminal.write("\r\n[Input queue full; retry input]\r\n");
});
terminal.onResize(({cols, rows}) => TerminalIO.resize(rows, cols));
function resize() { if (innerWidth > 0 && innerHeight > 0) fit.fit(); }
new ResizeObserver(resize).observe(document.getElementById("terminal"));
window.addEventListener("resize", resize);
window.writeTerminal = value => terminal.write(Uint8Array.from(atob(value), char => char.charCodeAt(0)));
window.resetTerminal = value => { terminal.reset(); window.writeTerminal(value); };
window.focusTerminal = () => terminal.focus();
resize();
TerminalIO.ready();
