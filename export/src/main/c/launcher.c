/*
 * Windows launcher for exported Scratch for Java apps: <Name>.exe next to
 * runtime\ and app\. It starts runtime\bin\javaw.exe with the arguments in
 * app\launch.args (a Java @argfile the export writes), in app\ as the working
 * directory - no console window, no admin rights, no configuration.
 *
 * Built by CI with mingw-w64 (scripts/build-win-launcher.sh) and shipped in
 * the export module as launcher/windows-x64.exe.
 */
#define UNICODE
#define _UNICODE
#include <windows.h>
#include <wchar.h>

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR args, int show) {
  (void) instance; (void) previous; (void) args; (void) show;
  wchar_t exe[MAX_PATH];
  DWORD length = GetModuleFileNameW(NULL, exe, MAX_PATH);
  if (length == 0 || length >= MAX_PATH) {
    return 1;
  }
  wchar_t *slash = wcsrchr(exe, L'\\');
  if (slash == NULL) {
    return 1;
  }
  *slash = 0; /* exe is now the folder */

  wchar_t java[MAX_PATH + 64];
  wchar_t workdir[MAX_PATH + 16];
  wchar_t command[3 * MAX_PATH];
  swprintf(java, sizeof(java) / sizeof(java[0]), L"%ls\\runtime\\bin\\javaw.exe", exe);
  swprintf(workdir, sizeof(workdir) / sizeof(workdir[0]), L"%ls\\app", exe);
  swprintf(command, sizeof(command) / sizeof(command[0]),
      L"\"%ls\" \"@%ls\\app\\launch.args\"", java, exe);

  STARTUPINFOW startup;
  PROCESS_INFORMATION process;
  ZeroMemory(&startup, sizeof(startup));
  startup.cb = sizeof(startup);
  ZeroMemory(&process, sizeof(process));
  if (!CreateProcessW(java, command, NULL, NULL, FALSE, 0, NULL, workdir, &startup, &process)) {
    MessageBoxW(NULL,
        L"The program could not start: runtime\\bin\\javaw.exe is missing.\n"
        L"Keep the .exe next to the runtime and app folders.",
        L"Scratch for Java", MB_ICONERROR | MB_OK);
    return 1;
  }
  CloseHandle(process.hThread);
  CloseHandle(process.hProcess);
  return 0;
}
