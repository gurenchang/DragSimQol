"""Mining report from Recorder-mod replays (.mcrc), side by side for several recordings.

    python -I tools/mcrc_report.py                 # newest recording in the play instance
    python -I tools/mcrc_report.py -n 2            # the two newest, compared
    python -I tools/mcrc_report.py a.mcrc b.mcrc   # given files (a name without a folder is looked up in recordings/)
    python -I tools/mcrc_report.py --timeline --from 2000 --to 4000

Reads only what crossed the wire (format spec: RECORDING_FORMAT.md inside mods/recorder.jar): the client's
START/ABORT/STOP actions and the server's block updates, crack stages, acks and inventory updates. Packet ids
come from GameProtocols.java in the decompiled sources for the recording's game version (registration order),
block state names from the server jar's data reports (generated once, cached in tools/.cache/). Lines of the
mod's own log (logs/zealotmacro.log) inside the recording's time window are summarised too.

A break = a block turning into the broken state (bedrock). Breaks in the same packet burst form one event;
"own" blocks had a START from the client in the 1.5 s before, the rest of an event is area break.
"""

import argparse
import datetime
import json
import os
import re
import statistics
import struct
import subprocess
import sys
import tempfile

INSTANCE = r"C:\Users\guren\AppData\Roaming\PrismLauncher\instances\26.1.2 dragsim\minecraft"
MCSRC = r"C:\Users\guren\mcsrc"
JAVA = os.path.expanduser(r"~\.gradle\jdks\jdk-25.0.4.1+1\bin\java")
LOOM = os.path.expanduser(r"~\.gradle\caches\fabric-loom")
CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".cache")

ACTIONS = ("START", "ABORT", "STOP")
OWN_WINDOW_MS = 1500     # a START this recent makes a broken block "own"
GROUP_MS = 5             # breaks this close together are one event
REACH = 6                # breaks farther than this from the last START are someone else's
INV_RESTART_MS = 60      # a same-block restart this soon after an inventory update is blamed on it
MIN_AUTO_MS = 3000       # shorter recordings are skipped when picking the newest ones


# ---- file and packet decoding ----

def read_mcrc(path):
	with open(path, "rb") as f:
		def string():
			return f.read(struct.unpack(">H", f.read(2))[0]).decode("utf-8", "replace")
		magic, version, start = struct.unpack(">iiq", f.read(16))
		if magic != 0x4D435243:
			raise ValueError(f"{path}: not an .mcrc file")
		header = {"version": version, "start": start, "protocol": None, "game": None, "player": None}
		if version >= 3:
			header["protocol"] = struct.unpack(">i", f.read(4))[0]
			header["game"] = string()
		if version >= 2:
			f.read(16)
			header["player"] = string()
			for _ in range(struct.unpack(">i", f.read(4))[0]):
				string(); string()
				if f.read(1)[0]:
					string()
		rows = []
		while True:
			head = f.read(9)
			if len(head) < 9:
				break
			t, direction, length = struct.unpack(">iBi", head)
			data = f.read(length)
			if len(data) < length:
				break           # crash-truncated tail
			rows.append((t, direction, data))
	return header, rows


class Buf:
	def __init__(self, data):
		self.b, self.i = data, 0

	def varint(self):   # also reads varlongs; values come back unsigned
		value = shift = 0
		while True:
			x = self.b[self.i]
			self.i += 1
			value |= (x & 0x7F) << shift
			if not x & 0x80:
				return value
			shift += 7

	def i64(self):
		value = struct.unpack_from(">q", self.b, self.i)[0]
		self.i += 8
		return value

	def u8(self):
		self.i += 1
		return self.b[self.i - 1]


def signed(value, bits):
	return value - (1 << bits) if value >= 1 << (bits - 1) else value


def block_pos(v):
	return (v >> 38, signed(v & 0xFFF, 12), signed((v >> 12) & 0x3FFFFFF, 26))


def section_pos(v):
	return (v >> 42, signed(v & 0xFFFFF, 20), signed((v >> 20) & 0x3FFFFF, 22))


def packet_ids(game, mcsrc):
	path = os.path.join(mcsrc, game, "minecraft", "net", "minecraft", "network", "protocol", "game", "GameProtocols.java")
	if not os.path.exists(path):
		sys.exit(f"No decompiled sources for {game}: {path} (see CLAUDE.md, 'Looking up Minecraft / Fabric APIs')")
	src = open(path, encoding="utf-8").read()

	def table(marker):
		i = src.index(marker)
		j = src.index(";", src.index("builder ->", i))
		found = re.finditer(r"\.(?:addPacket|withBundlePacket)\(\s*\w+PacketTypes\.(\w+)", src[i:j])
		return {m.group(1): n for n, m in enumerate(found)}
	return table("CLIENTBOUND_TEMPLATE ="), table("SERVERBOUND_TEMPLATE =")


def block_names(game):
	cache = os.path.join(CACHE, f"blocks-{game}.json")
	if not os.path.exists(cache):
		jar = os.path.join(LOOM, game, "minecraft-server.jar")
		print(f"Generating block state ids from {jar} ...", file=sys.stderr)
		with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
			subprocess.run([JAVA, "-DbundlerMainClass=net.minecraft.data.Main", "-jar", jar, "--reports",
					"--output", os.path.join(tmp, "out")], cwd=tmp, check=True,
					stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
			with open(os.path.join(tmp, "out", "reports", "blocks.json"), encoding="utf-8") as f:
				blocks = json.load(f)
		names = {s["id"]: name.removeprefix("minecraft:") for name, b in blocks.items() for s in b["states"]}
		os.makedirs(CACHE, exist_ok=True)
		with open(cache, "w", encoding="utf-8") as f:
			json.dump(names, f)
	with open(cache, encoding="utf-8") as f:
		return {int(k): v for k, v in json.load(f).items()}


def events(rows, clientbound, serverbound, names):
	"""(t, kind, pos, value): START/ABORT/STOP (value = sequence), BLOCK (block name), CRACK (stage),
	ACK (sequence), INV (inventory slot). Snapshot rows (direction 2/6) were never sent by the server."""
	action = serverbound["SERVERBOUND_PLAYER_ACTION"]
	block = clientbound["CLIENTBOUND_BLOCK_UPDATE"]
	section = clientbound["CLIENTBOUND_SECTION_BLOCKS_UPDATE"]
	crack = clientbound["CLIENTBOUND_BLOCK_DESTRUCTION"]
	ack = clientbound["CLIENTBOUND_BLOCK_CHANGED_ACK"]
	inventory = clientbound["CLIENTBOUND_SET_PLAYER_INVENTORY"]
	out = []
	for t, direction, data in rows:
		if direction not in (0, 1):
			continue
		b = Buf(data)
		pid = b.varint()
		if direction == 1:
			if pid == action:
				kind = b.varint()
				pos = block_pos(b.i64())
				b.u8()
				if kind < len(ACTIONS):
					out.append((t, ACTIONS[kind], pos, b.varint()))
		elif pid == block:
			pos = block_pos(b.i64())
			out.append((t, "BLOCK", pos, names.get(b.varint(), "?")))
		elif pid == section:
			sx, sy, sz = section_pos(b.i64())
			for _ in range(b.varint()):
				change = b.varint()
				rel = change & 0xFFF
				pos = (sx * 16 + (rel >> 8 & 15), sy * 16 + (rel & 15), sz * 16 + (rel >> 4 & 15))
				out.append((t, "BLOCK", pos, names.get(change >> 12, "?")))
		elif pid == crack:
			b.varint()
			pos = block_pos(b.i64())
			out.append((t, "CRACK", pos, b.u8()))
		elif pid == ack:
			out.append((t, "ACK", None, b.varint()))
		elif pid == inventory:
			out.append((t, "INV", None, b.varint()))
	return out


# ---- analysis ----

def cheb(a, b):
	return max(abs(x - y) for x, y in zip(a, b))


def analyze(ev, broken):
	state = {}
	run_start = {}          # pos -> first START of the current run of STARTs on it
	prev_pos, prev_t = None, None
	sent, rtts = {}, []
	starts = []             # (t, pos, on_broken)
	breaks = []             # (t, pos, started_at or None, bot pos at that moment)
	restarts = restarts_inv = 0
	last_inv = -10 ** 9
	for t, kind, pos, value in ev:
		if kind == "START":
			starts.append((t, pos, state.get(pos) == broken))
			if pos == prev_pos:
				restarts += 1
				if t - last_inv <= INV_RESTART_MS:
					restarts_inv += 1
			else:
				run_start[pos] = t
			prev_pos, prev_t = pos, t
			sent[value] = t
		elif kind == "ACK":
			if value in sent:
				rtts.append(t - sent.pop(value))
		elif kind == "INV":
			last_inv = t
		elif kind == "BLOCK":
			if value == broken and state.get(pos) != broken and prev_pos is not None and cheb(pos, prev_pos) <= REACH:
				started = run_start.get(pos)
				own = started if started is not None and t - started <= OWN_WINDOW_MS else None
				breaks.append((t, pos, own, prev_pos))
			state[pos] = value

	groups = []
	for br in breaks:
		if groups and br[0] - groups[-1][-1][0] <= GROUP_MS:
			groups[-1].append(br)
		else:
			groups.append([br])

	m = {}
	if not groups:
		m["breaks"] = 0
		return m
	span = (groups[-1][0][0] - groups[0][0][0]) / 1000
	blocks = sum(len(g) for g in groups)
	own = [b for b in breaks if b[2] is not None]
	m["mining span s"] = f"{span:.1f}"
	m["blocks broken"] = blocks
	m["blocks/s"] = f"{sum(len(g) for g in groups[1:]) / span:.2f}" if span > 0 else "-"
	m["break events/s"] = f"{(len(groups) - 1) / span:.2f}" if span > 0 else "-"
	m["blocks/event"] = f"{blocks / len(groups):.2f}"
	m["own : area blocks"] = f"{len(own)} : {blocks - len(own)}"
	m["START -> own break ms (med)"] = fmt_med([b[0] - b[2] for b in own])
	m["RTT START -> ack ms (med)"] = fmt_med(rtts)
	m["STARTs (on broken blocks)"] = f"{len(starts)} ({sum(s[2] for s in starts)})"
	m["same-block restarts (after inv)"] = f"{restarts} ({restarts_inv})"

	live, last = [], None
	for s in starts:
		if not s[2] and s[1] != last:
			live.append(s)
		last = s[1]
	intervals = [b[0] - a[0] for a, b in zip(live, live[1:]) if b[0] - a[0] < OWN_WINDOW_MS]
	m["new live-block START every ms (med)"] = fmt_med(intervals)

	adjacent = hits = 0
	gaps = []
	for g in groups:
		owns = [b for b in g if b[2] is not None]
		main = min(owns, key=lambda b: b[2]) if owns else g[0]
		bot = g[-1][3]
		if bot == main[1]:
			continue
		gone = {b[1] for b in g}
		if cheb(bot, main[1]) <= 1:
			adjacent += 1
		if bot in gone:
			hits += 1
			nxt = next((s for s in starts if s[0] > g[0][0] and not s[2] and s[1] not in gone), None)
			if nxt:
				gaps.append(nxt[0] - g[0][0])
	moved = sum(1 for g in groups if g[-1][3] != (min((b for b in g if b[2] is not None), key=lambda b: b[2], default=g[0]))[1])
	m["on next block when one breaks: adjacent / all"] = f"{adjacent} / {moved}"
	m["  ... next block taken by area break"] = f"{hits} ({100 * hits / moved:.0f}%)" if moved else "0"
	m["  ... idle after that, ms (sum, % of span)"] = (f"{sum(gaps)} ({100 * sum(gaps) / 1000 / span:.0f}%)"
			if span > 0 else str(sum(gaps)))
	return m


def fmt_med(values):
	return f"{statistics.median(values):.0f} (n={len(values)})" if values else "-"


# ---- mod log ----

LOG_LINE = re.compile(r"(\d\d):(\d\d):(\d\d)\.(\d{3}) (\S+)(.*)")


def log_summary(instance, start_ms, end_ms):
	start = datetime.datetime.fromtimestamp(start_ms / 1000)
	end = datetime.datetime.fromtimestamp(end_ms / 1000)
	lo = start.hour * 3600 + start.minute * 60 + start.second + start.microsecond / 1e6
	hi = lo + (end - start).total_seconds()
	lines = []
	for name in ("zealotmacro.old.log", "zealotmacro.log"):
		path = os.path.join(instance, "logs", name)
		# A log last written before the recording started can't hold it (logs carry no date).
		if not os.path.exists(path) or os.path.getmtime(path) * 1000 < start_ms:
			continue
		with open(path, encoding="utf-8", errors="replace") as f:
			for line in f:
				m = LOG_LINE.match(line)
				if m and lo <= int(m[1]) * 3600 + int(m[2]) * 60 + int(m[3]) + int(m[4]) / 1000 <= hi:
					lines.append((m[5], m[6]))
	m = {}
	if not lines:
		m["log lines in window"] = 0
		return m

	def num(field, rows):
		return [float(x) for _, rest in rows for x in re.findall(rf"\b{field}=(-?[\d.]+)", rest)]

	def of(event):
		return [l for l in lines if l[0] == event]
	start_line = of("MINING_BOT_START")
	if start_line:
		target = re.search(r"\btarget=(\S+)", start_line[0][1])
		m["log: bot target"] = target[1] if target else "?"
	cracked = [l for l in of("MINING_BOT_BREAK_TICKS") if not re.search(r"\bcracks=0\b", l[1])]
	early, left = num("afterMs", of("MINING_BOT_EARLY_ISSUED")), num("afterMs", of("MINING_BOT_LEFT_BLOCK"))
	m["log: early switches / hold ms (avg)"] = f"{len(early)} / {statistics.mean(early):.0f}" if early else "0"
	m["log: crosshair leaves after ms (avg)"] = f"{statistics.mean(left):.0f}" if left else "-"
	ms = num("ms", cracked)
	m["log: own break ms (avg, cracked)"] = f"{statistics.mean(ms):.0f} (n={len(ms)})" if ms else "-"
	m["log: blocks with restarts"] = sum(1 for r in num("restarts", cracked) if r > 0)
	avoid = num("extraDeg", of("MINING_BOT_AREA_AVOID"))
	m["log: area avoid / extra deg (avg)"] = f"{len(avoid)} / {statistics.mean(avoid):.1f}" if avoid else "0"
	m["log: break reset fix"] = len(of("BREAK_RESET_FIX"))
	for event in ("MINING_BOT_TIMEOUT", "MINING_BOT_EARLY_FAILED", "MINING_BOT_TICK_MISMATCH", "MINING_BOT_STOP"):
		found = of(event)
		if found:
			m[f"log: {event.removeprefix('MINING_BOT_').lower()}"] = (
				f"{len(found)}" + (f" ({found[-1][1].strip()[:30]})" if event == "MINING_BOT_STOP" else ""))
	return m


# ---- output ----

def print_table(columns):
	labels = []
	for _, metrics in columns:
		for k in metrics:
			if k not in labels:
				labels.append(k)
	width = max(len(k) for k in labels) + 2
	cols = [max(len(name), *(len(str(m.get(k, ""))) for k in labels)) + 2 for name, m in columns]
	print("".ljust(width) + "".join(name.ljust(c) for (name, _), c in zip(columns, cols)))
	for k in labels:
		print(k.ljust(width) + "".join(str(m.get(k, "")).ljust(c) for (_, m), c in zip(columns, cols)))


def load(paths, latest, instance):
	"""(path, header, rows) for the given files, or for the `latest` newest recordings of at least MIN_AUTO_MS."""
	folder = os.path.join(instance, "recordings")
	if paths:
		# Pasted Windows paths can carry invisible direction marks.
		paths = [p.strip("\u202a\u202c\u200e\u200f \"") for p in paths]
		paths = [p if os.path.dirname(p) else os.path.join(folder, p if p.endswith(".mcrc") else p + ".mcrc")
				for p in paths]
		return [(p, *read_mcrc(p)) for p in paths]
	picked = []
	files = sorted((os.path.join(folder, f) for f in os.listdir(folder) if f.endswith(".mcrc")), key=os.path.getmtime)
	for path in reversed(files):
		header, rows = read_mcrc(path)
		length = rows[-1][0] if rows else 0
		if length < MIN_AUTO_MS:
			print(f"skipped {os.path.basename(path)} ({length / 1000:.1f} s)", file=sys.stderr)
			continue
		picked.append((path, header, rows))
		if len(picked) == latest:
			break
	return picked[::-1]


def main():
	ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
	ap.add_argument("paths", nargs="*", help=".mcrc files (default: the newest of at least 3 s in the play instance)")
	ap.add_argument("-n", "--latest", type=int, default=1, help="compare the N newest recordings")
	ap.add_argument("--broken", default="bedrock", help="block a broken block turns into")
	ap.add_argument("--timeline", action="store_true", help="print the decoded events instead of the report")
	ap.add_argument("--from", dest="t_from", type=int, default=0, help="timeline start, ms into the recording")
	ap.add_argument("--to", dest="t_to", type=int, default=10 ** 9, help="timeline end, ms into the recording")
	ap.add_argument("--instance", default=INSTANCE)
	ap.add_argument("--mcsrc", default=MCSRC)
	args = ap.parse_args()
	sys.stdout.reconfigure(encoding="utf-8")

	columns = []
	for path, header, rows in load(args.paths, args.latest, args.instance):
		game = header["game"] or "26.1.2"
		clientbound, serverbound = packet_ids(game, args.mcsrc)
		ev = events(rows, clientbound, serverbound, block_names(game))
		name = os.path.splitext(os.path.basename(path))[0]
		if args.timeline:
			print(f"== {name}")
			for t, kind, pos, value in ev:
				if args.t_from <= t <= args.t_to:
					print(t, kind, "" if pos is None else pos, value)
			continue
		duration = rows[-1][0] if rows else 0
		metrics = {"player / game": f"{header['player']} / {game}", "length s": f"{duration / 1000:.1f}"}
		metrics.update(analyze(ev, args.broken))
		metrics.update(log_summary(args.instance, header["start"], header["start"] + duration))
		columns.append((name, metrics))
	if columns:
		print_table(columns)


if __name__ == "__main__":
	main()
