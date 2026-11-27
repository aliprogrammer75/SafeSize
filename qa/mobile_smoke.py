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
def tap_label(m,dev,label):
    for _ in range(4):
        try:
            root=xml()
            for n in root.iter("node"):
                if label not in (n.get("text"),n.get("content-desc")): continue
                coords=list(map(int,re.findall(r"\d+",n.get("bounds",""))))
                if len(coords)!=4: continue
                x1,y1,x2,y2=coords
                if x2>x1 and y2>y1:
                    m.tool("mobile_click_on_screen_at_coordinates",device=dev,x=(x1+x2)//2,y=(y1+y2)//2)
                    return
        except (ET.ParseError,subprocess.CalledProcessError): pass
        time.sleep(1)
    raise RuntimeError("Missing label: "+label)

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
    if dev not in devices: raise RuntimeError("MCP did not detect emulator: "+devices[:600])
    check("MCP device discovery",dev)
    m.tool("mobile_launch_app",device=dev,packageName=PACKAGE)
    time.sleep(5)
    s=elements(m,dev,"01-dashboard")
    m.screenshot(dev,"01-dashboard")
    if "داشبورد" not in s and not any(n.get("text")=="داشبورد" or n.get("content-desc")=="داشبورد" for n in xml().iter("node")):
        raise RuntimeError("Dashboard navigation missing")
    check("Native app launch and dashboard","MCP hierarchy + screenshot")
    tap_label(m,dev,"نمودار آنلاین")
    time.sleep(2)
    s=elements(m,dev,"02-online")
    m.screenshot(dev,"02-online")
    if "فضای کاری مشترک SafeSize" not in s and not any("فضای کاری مشترک SafeSize" in n.get("text","") for n in xml().iter("node")):
        raise RuntimeError("Online workspace screen not displayed")
    check("Native online workspace","Screen reached with MCP tap")
    tap_label(m,dev,"ماشین حساب")
    time.sleep(2)
    elements(m,dev,"03-calculator")
    m.screenshot(dev,"03-calculator")
    check("Calculator navigation","Screen captured")
    tap_label(m,dev,"ژورنال")
    time.sleep(2)
    elements(m,dev,"04-journal")
    m.screenshot(dev,"04-journal")
    check("Journal navigation","Screen captured")
    tap_label(m,dev,"نمودار آنلاین")
    time.sleep(1)
    tap_label(m,dev,"باز کردن فضای کاری آنلاین")
    time.sleep(12)
    s=elements(m,dev,"05-online-launch")
    m.screenshot(dev,"05-online-launch")
    REPORT["web"]="Browser launch attempted; live chart/auth/push require independent assertions"
    REPORT["foreground"]=m.text("mobile_get_foreground_app",device=dev) if "mobile_get_foreground_app" in names else "unknown"
    check("Online button action","Captured resulting UI; web load not asserted")
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
