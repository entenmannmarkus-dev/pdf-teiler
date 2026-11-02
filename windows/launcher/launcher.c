/* Splitt-Tool.exe – startet die mitgelieferte Java-Laufzeit mit der neuesten Programmdatei aus app\.
   Keine Installation, keine Adminrechte: alles liegt im Ordner neben dieser Datei. */
#include <windows.h>
#include <wchar.h>
#include <stdlib.h>

static int endsWith(const wchar_t *s, const wchar_t *suf) {
    size_t a = wcslen(s), b = wcslen(suf);
    return a >= b && _wcsicmp(s + a - b, suf) == 0;
}

int WINAPI wWinMain(HINSTANCE hInst, HINSTANCE hPrev, LPWSTR args, int show) {
    static wchar_t exe[MAX_PATH], dir[MAX_PATH], pat[MAX_PATH], java[MAX_PATH], jar[MAX_PATH], cmd[8192];
    GetModuleFileNameW(NULL, exe, MAX_PATH);
    wcscpy(dir, exe);
    wchar_t *slash = wcsrchr(dir, L'\\');
    if (slash) *slash = 0;

    swprintf(pat, MAX_PATH, L"%ls\\app\\splitt-tool-*.jar", dir);
    WIN32_FIND_DATAW fd;
    HANDLE f = FindFirstFileW(pat, &fd);
    int best = -1;
    if (f != INVALID_HANDLE_VALUE) {
        do {
            if (!endsWith(fd.cFileName, L".jar")) continue;
            int n = _wtoi(fd.cFileName + 12);
            if (n > best) { best = n; swprintf(jar, MAX_PATH, L"%ls\\app\\%ls", dir, fd.cFileName); }
        } while (FindNextFileW(f, &fd));
        FindClose(f);
    }
    swprintf(java, MAX_PATH, L"%ls\\runtime\\bin\\javaw.exe", dir);
    if (best < 0 || GetFileAttributesW(java) == INVALID_FILE_ATTRIBUTES) {
        MessageBoxW(NULL, L"Programmdateien fehlen. Bitte den Ordner \"Splitt-Tool\" vollständig entpacken "
                          L"(Ordner \"app\" und \"runtime\" müssen neben Splitt-Tool.exe liegen).",
                    L"Splitt-Tool", MB_ICONERROR);
        return 1;
    }
    swprintf(cmd, 8192, L"\"%ls\" -XX:MaxRAMPercentage=60 -Dfile.encoding=UTF-8 -Dsplitt.home=\"%ls\" -Dsplitt.exe=\"%ls\" -jar \"%ls\" %ls",
             java, dir, exe, jar, args ? args : L"");
    STARTUPINFOW si; PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof si); si.cb = sizeof si;
    if (!CreateProcessW(NULL, cmd, NULL, NULL, FALSE, 0, NULL, dir, &si, &pi)) {
        MessageBoxW(NULL, L"Splitt-Tool konnte nicht gestartet werden.", L"Splitt-Tool", MB_ICONERROR);
        return 1;
    }
    CloseHandle(pi.hThread); CloseHandle(pi.hProcess);
    return 0;
}
