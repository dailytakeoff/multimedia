// MultiMedia – remote-control helper for Windows.
// Reads one command per line on stdin and replays it with SendInput. Only the mod talks to it, and the mod only
// forwards events from players the streamer explicitly allowed (/mm control allow <player>).
//
//   mon <i>          select the monitor (same order as ffmpeg's ddagrab output_idx: DXGI outputs of adapter 0),
//                    answers "size <w> <h>"
//   move <x> <y>     pointer to pixel x,y of that monitor
//   down|up l|r|m    mouse button
//   wheel <n>        scroll n notches (positive = up)
//   key <combo>      e.g. "enter", "ctrl+c", "alt+f4", "shift+tab"
//   text <base64>    type UTF-8 text
//   release          let go of all mouse buttons and modifier keys
//
// Build (MinGW-w64): see scripts/build-audio-helper.sh

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <dxgi.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

static RECT g_mon = {0, 0, 0, 0};
static bool g_held[3] = {false, false, false};

static void dpiAware() {
	// physical pixels, like DXGI – per-monitor v2 if available (Windows 10 1703+), else system aware
	HMODULE user = GetModuleHandleA("user32.dll");
	typedef BOOL(WINAPI * SetCtx)(HANDLE);
	SetCtx set = user ? (SetCtx)(void *)GetProcAddress(user, "SetProcessDpiAwarenessContext") : nullptr;
	if (!set || !set((HANDLE)-4 /* DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 */)) {
		SetProcessDPIAware();
	}
}

static bool selectMonitor(int idx) {
	IDXGIFactory1 *factory = nullptr;
	if (FAILED(CreateDXGIFactory1(__uuidof(IDXGIFactory1), (void **)&factory))) {
		return false;
	}
	bool ok = false;
	IDXGIAdapter1 *adapter = nullptr;
	if (SUCCEEDED(factory->EnumAdapters1(0, &adapter))) {
		IDXGIOutput *output = nullptr;
		if (SUCCEEDED(adapter->EnumOutputs((UINT)idx, &output))) {
			DXGI_OUTPUT_DESC desc;
			if (SUCCEEDED(output->GetDesc(&desc))) {
				g_mon = desc.DesktopCoordinates;
				ok = true;
			}
			output->Release();
		}
		adapter->Release();
	}
	factory->Release();
	if (!ok) {
		// fallback: primary monitor
		g_mon.left = 0;
		g_mon.top = 0;
		g_mon.right = GetSystemMetrics(SM_CXSCREEN);
		g_mon.bottom = GetSystemMetrics(SM_CYSCREEN);
	}
	return ok;
}

static void mouseButton(char b, bool down) {
	INPUT in = {};
	in.type = INPUT_MOUSE;
	int i = b == 'l' ? 0 : b == 'r' ? 1 : 2;
	if (b == 'l') {
		in.mi.dwFlags = down ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP;
	} else if (b == 'r') {
		in.mi.dwFlags = down ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP;
	} else {
		in.mi.dwFlags = down ? MOUSEEVENTF_MIDDLEDOWN : MOUSEEVENTF_MIDDLEUP;
	}
	g_held[i] = down;
	SendInput(1, &in, sizeof(INPUT));
}

static void wheel(int n) {
	INPUT in = {};
	in.type = INPUT_MOUSE;
	in.mi.dwFlags = MOUSEEVENTF_WHEEL;
	in.mi.mouseData = (DWORD)(n * WHEEL_DELTA);
	SendInput(1, &in, sizeof(INPUT));
}

static WORD vkFor(const std::string &k) {
	if (k.size() == 1 && k[0] >= 'a' && k[0] <= 'z') return (WORD)('A' + (k[0] - 'a'));
	if (k.size() == 1 && k[0] >= '0' && k[0] <= '9') return (WORD)k[0];
	if (k.size() >= 2 && k[0] == 'f') {
		int n = atoi(k.c_str() + 1);
		if (n >= 1 && n <= 12) return (WORD)(VK_F1 + n - 1);
	}
	struct { const char *name; WORD vk; } table[] = {
		{"enter", VK_RETURN}, {"backspace", VK_BACK}, {"tab", VK_TAB}, {"escape", VK_ESCAPE}, {"space", VK_SPACE},
		{"delete", VK_DELETE}, {"insert", VK_INSERT}, {"home", VK_HOME}, {"end", VK_END}, {"pageup", VK_PRIOR},
		{"pagedown", VK_NEXT}, {"left", VK_LEFT}, {"right", VK_RIGHT}, {"up", VK_UP}, {"down", VK_DOWN},
		{"ctrl", VK_CONTROL}, {"shift", VK_SHIFT}, {"alt", VK_MENU}, {"super", VK_LWIN},
	};
	for (auto &e : table) {
		if (k == e.name) return e.vk;
	}
	return 0;
}

static bool extended(WORD vk) {
	switch (vk) {
		case VK_DELETE: case VK_INSERT: case VK_HOME: case VK_END: case VK_PRIOR: case VK_NEXT:
		case VK_LEFT: case VK_RIGHT: case VK_UP: case VK_DOWN: case VK_LWIN:
			return true;
		default:
			return false;
	}
}

static void keyEvent(WORD vk, bool down) {
	INPUT in = {};
	in.type = INPUT_KEYBOARD;
	in.ki.wVk = vk;
	in.ki.dwFlags = (down ? 0 : KEYEVENTF_KEYUP) | (extended(vk) ? KEYEVENTF_EXTENDEDKEY : 0);
	SendInput(1, &in, sizeof(INPUT));
}

static void keyCombo(const std::string &combo) {
	std::vector<WORD> keys;
	size_t start = 0;
	while (start <= combo.size()) {
		size_t plus = combo.find('+', start);
		std::string part = combo.substr(start, plus == std::string::npos ? std::string::npos : plus - start);
		WORD vk = vkFor(part);
		if (!vk) return; // unknown key: do nothing
		keys.push_back(vk);
		if (plus == std::string::npos) break;
		start = plus + 1;
	}
	for (WORD vk : keys) keyEvent(vk, true);
	for (size_t i = keys.size(); i-- > 0;) keyEvent(keys[i], false);
}

static std::string b64decode(const std::string &s) {
	static const std::string chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
	std::string out;
	int val = 0, bits = -8;
	for (char c : s) {
		size_t p = chars.find(c);
		if (p == std::string::npos) break; // '=' or junk ends it
		val = (val << 6) + (int)p;
		bits += 6;
		if (bits >= 0) {
			out.push_back((char)((val >> bits) & 0xFF));
			bits -= 8;
		}
	}
	return out;
}

static void typeText(const std::string &utf8) {
	int n = MultiByteToWideChar(CP_UTF8, 0, utf8.c_str(), (int)utf8.size(), nullptr, 0);
	if (n <= 0 || n > 400) return;
	std::wstring w((size_t)n, L'\0');
	MultiByteToWideChar(CP_UTF8, 0, utf8.c_str(), (int)utf8.size(), &w[0], n);
	std::vector<INPUT> ins;
	for (wchar_t ch : w) {
		INPUT d = {};
		d.type = INPUT_KEYBOARD;
		d.ki.wScan = (WORD)ch;
		d.ki.dwFlags = KEYEVENTF_UNICODE;
		INPUT u = d;
		u.ki.dwFlags |= KEYEVENTF_KEYUP;
		ins.push_back(d);
		ins.push_back(u);
	}
	SendInput((UINT)ins.size(), ins.data(), sizeof(INPUT));
}

static void releaseAll() {
	const char b[3] = {'l', 'r', 'm'};
	for (int i = 0; i < 3; i++) {
		if (g_held[i]) mouseButton(b[i], false);
	}
	WORD mods[] = {VK_CONTROL, VK_SHIFT, VK_MENU, VK_LWIN};
	for (WORD vk : mods) {
		if (GetAsyncKeyState(vk) & 0x8000) keyEvent(vk, false);
	}
}

int main() {
	dpiAware();
	selectMonitor(0);
	setvbuf(stdout, nullptr, _IONBF, 0);
	char line[2048];
	while (fgets(line, sizeof line, stdin)) {
		std::string s(line);
		while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) s.pop_back();
		size_t sp = s.find(' ');
		std::string cmd = s.substr(0, sp);
		std::string arg = sp == std::string::npos ? "" : s.substr(sp + 1);
		if (cmd == "mon") {
			selectMonitor(atoi(arg.c_str()));
			printf("size %ld %ld\n", g_mon.right - g_mon.left, g_mon.bottom - g_mon.top);
		} else if (cmd == "move") {
			int x = 0, y = 0;
			if (sscanf(arg.c_str(), "%d %d", &x, &y) == 2) {
				long w = g_mon.right - g_mon.left, h = g_mon.bottom - g_mon.top;
				if (x < 0) x = 0;
				if (y < 0) y = 0;
				if (x >= w) x = (int)w - 1;
				if (y >= h) y = (int)h - 1;
				SetCursorPos(g_mon.left + x, g_mon.top + y);
			}
		} else if ((cmd == "down" || cmd == "up") && arg.size() == 1 && strchr("lrm", arg[0])) {
			mouseButton(arg[0], cmd == "down");
		} else if (cmd == "wheel") {
			int n = atoi(arg.c_str());
			if (n != 0 && n >= -20 && n <= 20) wheel(n);
		} else if (cmd == "key") {
			keyCombo(arg);
		} else if (cmd == "text") {
			typeText(b64decode(arg));
		} else if (cmd == "release") {
			releaseAll();
		}
	}
	releaseAll();
	return 0;
}
