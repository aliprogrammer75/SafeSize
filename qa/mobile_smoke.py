"""Ephemeral Android QA. No real account, no API keys, no destructive device commands."""
import base64, json, os, pathlib, queue, re, subprocess, threading, time, traceback
import xml.etree.ElementTree as ET

OUT = pathlib.Path("qa-output")
OUT.mkdir(exist_ok=True)
REPORT = {"checks": [], "errors": [], "web": "not tested"}
PACKAGE = "com.example"

def command(*args, timeout=45):
    return subprocess.check_output(args, timeout=timeout, stderr=subprocess.STDOUT)
def adb(*args, timeout=45):
    return command("adb", *args, timeout=timeout)

class MCP:
    def __init__(self):
        self.p = subprocess.Popen(["mcp-server-mobile"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=(OUT/"mobile-mcp-stderr.log").open("w"), text=True, bufsize=1)
        self.q = queue.Queue()
        self.i = 0
        threading.Thread(target=self.read, daemon=True).start()
        self.rpc("initialize", {"protocolVersion":"2025-03-26","capabilities":{},
              "clientInfo":{"name":"SafeSize CI","version":"1.0"}})
        self.send({"jsonrpc":"2.0","method":"notifications/initialized"})
    def read(self):
        for line in self.p.stdout:
            try: self.q.put(json.loads(line))
            except ValueError: pass
    def send(self, msg):
        self.p.stdin.write(json.dumps(msg)+"\n")
        self.p.stdin.flush()
    def rpc(self, method, params):
        self.i += 1
        idx = self.i
        self.send({"jsonrpc":"2.0","id":idx,"method":method,"params":params})
        deadline = time.monotonic()+120
        while time.monotonic()<deadline:
            try: r=self.q.get(timeout=max(.1,deadline-time.monotonic()))
            except queue.Empty: break
            if r.get("id")!=idx: continue
            if "error" in r: raise RuntimeError(str(r["error"])[:1000])
            return r["result"]
        raise TimeoutError(method)
    def tool(self,name,**args):
        r=self.rpc("tools/call",{"name":name,"arguments":args})
        if r.get("isError"): raise RuntimeError(name+": "+str(r.get("content"))[:1000])
        return r.get("content",[])
    def text(self,name,**args):
        return "\n".join(c.get("text","") for c in self.tool(name,**args) if c.get("type")=="text")
    def screenshot(self,device,name):
        blocks=self.tool("mobile_take_screenshot",device=device,maxSize=1200)
        for b in blocks:
            if b.get("type")=="image":
                ext=".png" if b.get("mimeType")=="image/png" else ".jpg"
                (OUT/(name+ext)).write_bytes(base64.b64decode(b["data"],validate=True))
                return
        raise RuntimeError("No MCP screenshot received")
    def close(self):
        self.p.terminate()
        try: self.p.wait(timeout=5)
        except subprocess.TimeoutExpired: self.p.kill()

def check(name, detail):
    REPORT["checks"].append({"name":name,"status":"pass","detail":detail})
    print("PASS:",name,detail,flush=True)
def elements(m,dev,name):
    s=m.text("mobile_list_elements_on_screen",device=dev,format="text")
    (OUT/(name+".txt")).write_text(s,encoding="utf-8")
    return s
def xml():
    adb("shell","uiautomator","dump","/sdcard/safesize-qa.xml",timeout=35)
    return ET.fromstring(adb("shell","cat","/sdcard/safesize-qa.xml"))
def label_rows(screen,label):
    """Mobile MCP's compact output is the source of truth for Compose nodes."""
    needle=json.dumps(label,ensure_ascii=False)
    rows=[]
    for line in screen.splitlines():
        if not any((key+"="+needle) in line for key in ("text","label","name")):
            continue
        match=re.match(r"^(@\S+)\s",line)
        bounds=re.search(r"\bat=(\d+),(\d+) size=(\d+)x(\d+)",line)
        if match and bounds and "[disabled]" not in line:
            x,y,w,h=map(int,bounds.groups())
            if w>0 and h>0: rows.append((match.group(1),x+w//2,y+h//2))
    return rows

def tap_label(m,dev,label):
    last=""
    for attempt in range(5):
        last=m.text("mobile_list_elements_on_screen",device=dev,format="text")
        rows=label_rows(last,label)
        if len(rows)==1:
            ref,x,y=rows[0]
            print("MCP tap:",label,ref,x,y,flush=True)
            # Use coordinates from the fresh MCP hierarchy rather than a second
            # accessibility provider, which can merge Compose labels.
            m.tool("mobile_click_on_screen_at_coordinates",device=dev,x=x,y=y)
            return
        if len(rows)>1:
            raise RuntimeError("Ambiguous label: "+label)
        time.sleep(1)
    (OUT/"missing-label.txt").write_text(last,encoding="utf-8")
    raise RuntimeError("Missing MCP label: "+label)

def wait_screen(m,dev,name,required,attempts=8):
    for _ in range(attempts):
        s=elements(m,dev,name)
        if any(label_rows(s,label) for label in required):
            m.screenshot(dev,name)
            return s
        time.sleep(1)
    m.screenshot(dev,name)
    raise RuntimeError("Screen not confirmed: "+name+"; expected one of "+str(required))

m=None
try:
    adb("wait-for-device")
    serials=[l.split()[0] for l in adb("devices").decode().splitlines() if l.startswith("emulator-") and "\tdevice" in l]
    if len(serials)!=1: raise RuntimeError("Expected exactly one ephemeral emulator")
    dev=serials[0]
    assert adb("shell","getprop","sys.boot_completed").strip()==b"1"
    check("Emulator boot",dev)
    adb("install","-r","app/build/outputs/apk/debug/app-debug.apk",timeout=90)
    check("APK installation",PACKAGE)
    adb("logcat","-c")
    m=MCP()
    tools=m.rpc("tools/list",{})["tools"]
    names={t["name"] for t in tools}
    required={"mobile_list_available_devices","mobile_launch_app","mobile_list_elements_on_screen","mobile_click_on_screen_at_coordinates","mobile_take_screenshot"}
    if not required<=names: raise RuntimeError("Missing MCP tools")
    (OUT/"tools.json").write_text(json.dumps(sorted(names),indent=2))
    check("MCP handshake",str(len(names))+" tools")
    devices=m.text("mobile_list_available_devices")
    (OUT/"devices.txt").write_text(devices)
    discovered=json.loads(devices).get("devices", [])
    online=[d for d in discovered if d.get("platform")=="android" and d.get("type")=="emulator" and d.get("state")=="online"]
    if len(online)!=1 or not online[0].get("id"):
        raise RuntimeError("Expected exactly one online MCP Android emulator: "+devices[:600])
    adb_serial=dev
    avd=adb("-s",adb_serial,"emu","avd","name").decode().splitlines()[0].strip()
    candidate=online[0]
    if candidate["id"]!=adb_serial and candidate.get("name")!=avd:
        raise RuntimeError("MCP emulator does not match ADB AVD")
    dev=candidate["id"]
    check("MCP device discovery","ADB="+adb_serial+" AVD="+avd+" MCP="+dev)
    m.tool("mobile_launch_app",device=dev,packageName=PACKAGE)
    time.sleep(3)
    s=wait_screen(m,dev,"01-dashboard",["کل دارایی (EQUITY)"])
    if not label_rows(s,"نمودار آنلاین"):
        raise RuntimeError("Online navigation missing")
    check("Native app launch and dashboard","MCP hierarchy + screenshot")
    tap_label(m,dev,"نمودار آنلاین")
    s=wait_screen(m,dev,"02-online",["فضای کاری مشترک SafeSize"])
    check("Native online workspace","Screen reached with MCP tap")
    tap_label(m,dev,"ماشین حساب")
    wait_screen(m,dev,"03-calculator",["ماشین حساب ریسک"])
    check("Calculator navigation","Calculator title verified")
    tap_label(m,dev,"ژورنال")
    wait_screen(m,dev,"04-journal",["تاریخچه معاملات","پوزیشن‌های در جریان"])
    check("Journal navigation","Journal content verified")
    tap_label(m,dev,"نمودار آنلاین")
    wait_screen(m,dev,"02-online-return",["فضای کاری مشترک SafeSize"])
    tap_label(m,dev,"باز کردن فضای کاری آنلاین")
    time.sleep(12)
    s=elements(m,dev,"05-online-launch")
    m.screenshot(dev,"05-online-launch")
    REPORT["web"]="Browser launch attempted; live chart/auth/push require independent assertions"
    REPORT["foreground"]=m.text("mobile_get_foreground_app",device=dev) if "mobile_get_foreground_app" in names else "unknown"
    if "("+PACKAGE+")" in REPORT["foreground"]:
        raise RuntimeError("Online button did not open browser: "+REPORT["foreground"])
    check("Online button action","Foreground switched: "+REPORT["foreground"])
    from mobile_web import test_web
    test_web(m,dev,adb,check,elements,tap_label,wait_screen,REPORT,OUT,PACKAGE)
except Exception as e:
    REPORT["errors"].append(str(e))
    traceback.print_exc()
finally:
    try: (OUT/"logcat.txt").write_bytes(adb("logcat","-d","-t","1500",timeout=20))
    except Exception: pass
    try: (OUT/"final.png").write_bytes(adb("exec-out","screencap","-p",timeout=15))
    except Exception: pass
    if m: m.close()
    (OUT/"report.json").write_text(json.dumps(REPORT,ensure_ascii=False,indent=2),encoding="utf-8")
if REPORT["errors"]: raise SystemExit(1)
