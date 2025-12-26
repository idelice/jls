@echo off
setlocal EnableDelayedExpansion
set JLINK_VM_OPTIONS=^
--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED ^
--add-exports jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED ^
--add-exports jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED ^
--add-exports jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED ^
--add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED ^
--add-exports jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED ^
--add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED ^
--add-opens jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
set CLASSPATH_OPTIONS=-classpath %~dp0classpath\*
set "JAVA_EXECUTABLE=java"
set "LOMBOK_PATH="
set "JAVA_AGENT="

for %%A in (%*) do (
  set "ARG=%%~A"
  if /I "!ARG:~0,13!"=="-DlombokPath=" (
    set "LOMBOK_PATH=!ARG:~13!"
  )
)

where python3 >nul 2>nul
if %errorlevel%==0 if defined JAVA_LSP_RUNTIMES_FILE if defined JAVA_LSP_WORKSPACE_ROOT (
  for /f "delims=" %%i in ('python3 -c "import json,os,pathlib,re,sys
def read_file(p):
    try: return pathlib.Path(p).read_text()
    except Exception: return None
def parse_release_string(v):
    if not v: return None
    s=v.strip()
    if s.startswith(\"1.\"): s=s[2:]
    s=re.split(r\"[^0-9]\", s, 1)[0]
    if not s: return None
    try: return int(s)
    except Exception: return None
def detect_release(root):
    root=pathlib.Path(root)
    java_version=read_file(root/\".java-version\")
    if java_version:
        r=parse_release_string(java_version.splitlines()[0] if java_version.splitlines() else java_version)
        if r: return r
    tool_versions=read_file(root/\".tool-versions\")
    if tool_versions:
        for line in tool_versions.splitlines():
            line=line.strip()
            if line.startswith(\"java \"):
                parts=line.split()
                if len(parts)>=2:
                    r=parse_release_string(parts[1])
                    if r: return r
    pom=read_file(root/\"pom.xml\")
    if pom:
        for tag in (\"maven.compiler.release\",\"maven.compiler.source\",\"java.version\"):
            m=re.search(rf\"<{tag}>\\s*([^<]+)\\s*</{tag}>\", pom)
            if m:
                r=parse_release_string(m.group(1))
                if r: return r
    for gradle_name in (\"build.gradle\",\"build.gradle.kts\"):
        gradle=read_file(root/gradle_name)
        if not gradle: continue
        for key in (\"sourceCompatibility\",\"targetCompatibility\"):
            m=re.search(rf\"{key}\\s*=\\s*(\\\"([^\\\"]+)\\\"|'([^']+)'|([A-Za-z0-9_\\.]+))\", gradle)
            if m:
                v=m.group(2) or m.group(3) or m.group(4)
                r=parse_release_string(v)
                if r: return r
    return None
root=os.environ.get(\"JAVA_LSP_WORKSPACE_ROOT\")
config=os.environ.get(\"JAVA_LSP_RUNTIMES_FILE\")
if not root or not config: sys.exit(0)
release=detect_release(root)
if not release: sys.exit(0)
cfg=read_file(config)
if not cfg: sys.exit(0)
try: runtimes=json.loads(cfg)
except Exception: sys.exit(0)
for rt in runtimes:
    if not isinstance(rt, dict): continue
    if str(rt.get(\"version\"))==str(release):
        path=rt.get(\"path\")
        if not path: continue
        java=pathlib.Path(path)/\"bin\"/\"java\"
        if java.is_file():
            print(str(java))
            sys.exit(0)
sys.exit(0)"') do set "JAVA_EXECUTABLE=%%i"
)

if not "%JAVA_LSP_HOST_JAVA%"=="" if exist "%JAVA_LSP_HOST_JAVA%\\bin\\java.exe" set "JAVA_EXECUTABLE=%JAVA_LSP_HOST_JAVA%\\bin\\java.exe"
if not "%JAVA_HOME%"=="" if exist "%JAVA_HOME%\\bin\\java.exe" set "JAVA_EXECUTABLE=%JAVA_HOME%\\bin\\java.exe"

if defined LOMBOK_PATH (
  if exist "!LOMBOK_PATH!" (
    set "JAVA_AGENT=-javaagent:!LOMBOK_PATH!"
  ) else (
    echo Warning: ignoring lombok jar (unreadable): !LOMBOK_PATH! 1>&2
  )
)

if defined JAVA_AGENT (
  %JAVA_EXECUTABLE% %JLINK_VM_OPTIONS% %JAVA_AGENT% %CLASSPATH_OPTIONS% %*
) else (
  %JAVA_EXECUTABLE% %JLINK_VM_OPTIONS% %CLASSPATH_OPTIONS% %*
)
