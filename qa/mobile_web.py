"""Real Android Chrome regression suite, using local-only ADB/CDP on the disposable runner.
No production credentials, no simulated market responses and no bypass of host challenges."""
import json, time, pathlib, urllib.request, urllib.parse, base64, re, os
import websocket

SITE="https://alifatemii.ir/safesize/"
CHROME="com.android.chrome"

class CDP:
    def __init__(self,url):
        self.ws=websocket.create_connection(url,timeout=15,suppress_origin=True)
        self.seq=0
        self.errors=[]
        self.network=[]
        self.dialogs=[]
        self.registrations={}
        self.market_starts=[]
    def call(self,method,params=None):
        self.seq+=1
        self.ws.send(json.dumps({"id":self.seq,"method":method,"params":params or {}}))
        end=time.monotonic()+30
        while time.monotonic()<end:
            r=json.loads(self.ws.recv())
            if r.get("id")==self.seq:
                if "error" in r: raise RuntimeError(method+": "+str(r["error"]))
                return r.get("result",{})
            method2=r.get("method");p=r.get("params",{})
            if method2=="Page.javascriptDialogOpening":
                self.dialogs.append({"type":p.get("type"),"message":p.get("message")})
            if method2=="ServiceWorker.workerRegistrationUpdated":
                for reg in p.get("registrations",[]):
                    self.registrations[reg["registrationId"]]=reg
            if method2=="Runtime.exceptionThrown":
                d=p.get("exceptionDetails",{})
                self.errors.append({"text":d.get("text"),"description":d.get("exception",{}).get("description","")[:800]})
            if method2=="Network.requestWillBeSent":
                u=urllib.parse.urlsplit(p.get("request",{}).get("url",""))
                if u.hostname=="alifatemii.ir" and u.path.startswith("/safesize") and "market" in urllib.parse.parse_qs(u.query):
                    self.market_starts.append({"id":p.get("requestId"),"query":u.query[:160],"wallTime":p.get("wallTime",0)})
            if method2=="Network.responseReceived":
                v=p.get("response",{});u=urllib.parse.urlsplit(v.get("url",""))
                if u.hostname=="alifatemii.ir" and u.path.startswith("/safesize"):
                    self.network.append({"path":u.path,"query":u.query[:160],"status":v.get("status"),"type":p.get("type")})
        raise TimeoutError(method)
    def js(self,expr):
        r=self.call("Runtime.evaluate",{"expression":expr,"returnByValue":True,"awaitPromise":True})
        if r.get("exceptionDetails"): raise RuntimeError("JS evaluation: "+str(r["exceptionDetails"])[:600])
        return r.get("result",{}).get("value")
    def touch(self,typ,points):
        self.call("Input.dispatchTouchEvent",{"type":typ,"touchPoints":[{"x":x,"y":y,"id":i,"radiusX":3,"radiusY":3} for i,(x,y) in enumerate(points)]})
    def tap(self,x,y):
        self.touch("touchStart",[(x,y)]);time.sleep(.1);self.touch("touchEnd",[])
    def screenshot(self,path):
        result=self.call("Page.captureScreenshot",{"format":"png"})
        path.write_bytes(base64.b64decode(result["data"]))
    def close(self):
        self.ws.close()

def wait_for(fn,seconds,label):
    end=time.monotonic()+seconds;last=None
    while time.monotonic()<end:
        try:
            last=fn()
            if last: return last
        except (OSError,ValueError,websocket.WebSocketException): pass
        time.sleep(1)
    raise RuntimeError("Timed out: "+label+"; last="+str(last)[:350])

def targets():
    with urllib.request.urlopen("http://127.0.0.1:9222/json/list",timeout=3) as r: return json.load(r)
def site_target():
    for t in targets():
        u=urllib.parse.urlsplit(t.get("url",""))
        if t.get("type")=="page" and u.scheme=="https" and u.hostname=="alifatemii.ir" and u.path.rstrip("/")=="/safesize" and t.get("webSocketDebuggerUrl"):
            return t
    return None

# The browser exposes id="chart" as a named Window property before the chart
# library initializes. A DOM element is not a chart instance.
PROBE = """(()=>{const root=document.documentElement;let c=null,initializedOk=false,rest=null;
try{if(typeof chart!=='undefined'&&chart&&typeof chart.getDom==='function'&&typeof chart.getDataList==='function'&&typeof chart.getVisibleRange==='function'&&typeof chart.getBarSpace==='function')c=chart;}catch(_){}
try{initializedOk=typeof initialized!=='undefined'&&initialized===true;}catch(_){}
try{if(typeof readMarket==='function')rest=readMarket.ssRest31?.()??null;}catch(_){}
const r=c?.getDom('candle_pane','main')?.getBoundingClientRect(),v=c?.getVisibleRange(),a=c?.getDataList()||[];
return {url:location.href,title:document.title,documentReady:document.readyState,engine:!!c,ready:initializedOk&&!!c,phase:root?.dataset.ssThirtyFourReady??null,view:[innerWidth,innerHeight],screen:[screen.width,screen.height],dpr:devicePixelRatio,touch:navigator.maxTouchPoints,scrollWidth:root?.scrollWidth??0,plot:r?{x:r.x,y:r.y,w:r.width,h:r.height}:null,bars:a.length,last:a.length?{timestamp:a.at(-1).timestamp,close:a.at(-1).close}:null,range:v?{from:v.from,to:v.to}:null,space:c?c.getBarSpace():null,rest,body:document.body?.innerText.slice(0,250)??''}})()"""
def inspect(c):
    return c.js(PROBE)
def ready(c,report):
    s=inspect(c)
    report["loading_state"]=s
    return s if s["ready"] and s["bars"]>=20 and s["plot"] and s["plot"]["w"]>100 and s["plot"]["h"]>80 else None
def geometry(s):
    assert s["touch"]>0,"Not a touch device"
    assert s["scrollWidth"]<=s["view"][0]+2,"Horizontal overflow "+str(s)
    p=s["plot"]
    assert p and p["x"]>=-2 and p["x"]+p["w"]<=s["view"][0]+2,"Plot outside viewport"
    assert p["h"]>=80 and p["y"]>=-2 and p["y"]+p["h"]<=s["view"][1]+3,"Chart clipped vertically: "+str(p)
def tap_dom(c,selector):
    p=c.js("""(s=>{const e=document.querySelector(s);if(!e||e.disabled)return null; e.scrollIntoView({block:'nearest',inline:'nearest'});const r=e.getBoundingClientRect();const x=r.x+r.width/2,y=r.y+r.height/2;return r.width>0&&r.height>0&&e.contains(document.elementFromPoint(x,y))?{x,y}:null})("""+json.dumps(selector)+")")
    if not p: raise RuntimeError("Control not visible or blocked: "+selector)
    c.tap(p["x"],p["y"])
def assert_dialog(c,name):
    d=wait_for(lambda:c.js("""(()=>{const d=document.querySelector('dialog[open]');if(!d)return null;const r=d.getBoundingClientRect();return {label:d.getAttribute('aria-label'),x:r.x,y:r.y,w:r.width,h:r.height,vw:innerWidth,vh:innerHeight}})()"""),8,name)
    if d["x"]<-2 or d["y"]<-2 or d["x"]+d["w"]>d["vw"]+2 or d["y"]+d["h"]>d["vh"]+2:
        raise RuntimeError("Dialog out of viewport: "+str(d))
    return d

def test_web(m,dev,adb,check,elements,tap_label,wait_screen,report,out,package):
    c=None
    report["web"]="started"
    try:
        # Complete only standard anonymous Chrome onboarding. Never sign in.
        for _ in range(6):
            s=elements(m,dev,"06-chrome-onboarding")
            if 'text="Use without an account"' in s: tap_label(m,dev,"Use without an account")
            elif 'text="No thanks"' in s: tap_label(m,dev,"No thanks")
            elif 'text="Not now"' in s: tap_label(m,dev,"Not now")
            else: break
            time.sleep(3)
        adb("forward","tcp:9222","localabstract:chrome_devtools_remote")
        try: target=wait_for(site_target,18,"SafeSize Chrome tab after onboarding")
        except RuntimeError:
            # First run can consume the launch intent. Reopen via the native app, not an arbitrary URL.
            m.tool("mobile_launch_app",device=dev,packageName=package)
            time.sleep(2)
            s=elements(m,dev,"06-relaunch-native")
            if 'text="فضای کاری مشترک SafeSize"' not in s:
                tap_label(m,dev,"نمودار آنلاین")
            wait_screen(m,dev,"06-reopen-online",["فضای کاری مشترک SafeSize"])
            tap_label(m,dev,"باز کردن فضای کاری آنلاین")
            target=wait_for(site_target,35,"SafeSize opened by native app")
        check("Chrome onboarding and actual SafeSize URL",target["url"])
        c=CDP(target["webSocketDebuggerUrl"])
        c.call("Runtime.enable");c.call("Network.enable");c.call("Page.enable")
        # One reload to observe errors and actual responses from navigation onwards.
        c.call("Page.reload",{"ignoreCache":False})
        s=wait_for(lambda:ready(c,report),90,"Live chart and real market data")
        report["portrait"]=s;geometry(s)
        c.screenshot(out/"07-web-portrait.png");m.screenshot(dev,"07-mobile-portrait")
        check("Live Android chart","%d candles; %s viewport; plot %sx%s"%(s["bars"],s["view"],s["plot"]["w"],s["plot"]["h"]))
        check("Portrait layout","No horizontal overflow; touch enabled; plot in viewport")
        # Test real pinch gestures using Chrome's touch event dispatch, with no desktop viewport override.
        p=s["plot"];x=p["x"]+p["w"]*.5;y=p["y"]+p["h"]*.55
        start=min(32,p["w"]*.12);end=min(76,p["w"]*.28)
        c.touch("touchStart",[(x-start,y),(x+start,y)])
        for i in range(1,9):
            d=start+(end-start)*i/8
            c.touch("touchMove",[(x-d,y),(x+d,y)]);time.sleep(.06)
        c.touch("touchEnd",[]);time.sleep(.5)
        z=inspect(c);report["zoom"]={"before":s["space"],"after":z["space"]}
        assert z["space"]>s["space"]+.05,"Pinch failed: "+str(report["zoom"])
        check("Touch pinch zoom",str(report["zoom"]))
        p=z["plot"];x=p["x"]+p["w"]*.35;y=p["y"]+p["h"]*.55;dx=min(100,p["w"]*.35)
        c.touch("touchStart",[(x,y)])
        for i in range(1,9):
            c.touch("touchMove",[(x+dx*i/8,y)]);time.sleep(.04)
        c.touch("touchEnd",[]);time.sleep(.5)
        pan=inspect(c);report["pan"]={"before":z["range"],"after":pan["range"]}
        assert z["range"]!=pan["range"],"Touch pan did not move visible candle range"
        check("Touch pan",str(report["pan"]))
        # Drawing selection is a JS setup step, but both drawing points use real touch.
        before=c.js("typeof drawings!=='undefined'?drawings.length:null")
        c.js("startPlacement('segment');true")
        p=pan["plot"];y=p["y"]+p["h"]*.55
        c.tap(p["x"]+p["w"]*.25,y);time.sleep(.25)
        c.tap(p["x"]+p["w"]*.65,y-p["h"]*.12)
        time.sleep(.4)
        after=c.js("typeof drawings!=='undefined'?drawings.length:null")
        report["drawing"]={"before":before,"after":after}
        assert isinstance(before,int) and after==before+1,"Touch trend line not saved: "+str(report["drawing"])
        c.screenshot(out/"08-web-drawing.png")
        check("Touch trend line","Two points saved in disposable browser")
        # Open controls by visible touch, not forced DOM clicks.
        tap_dom(c,"#ss-auto33-chip");d=assert_dialog(c,"Sync dialog")
        c.screenshot(out/"09-sync-dialog.png")
        c.js("document.querySelector('dialog[open]').close();true")
        check("Sync dialog layout",str(d))
        tap_dom(c,"#ss-tools26");d=assert_dialog(c,"Analysis dialog")
        c.screenshot(out/"10-analysis-dialog.png")
        c.js("document.querySelector('dialog[open]').close();true")
        check("Analysis dialog layout",str(d))
        # Anonymous access must not read another account's workspace.
        anon=c.js("""fetch('/safesize/?workspace=1',{credentials:'same-origin',cache:'no-store'}).then(r=>({status:r.status,type:r.headers.get('content-type')}))""")
        report["anonymous_workspace"]=anon
        assert anon["status"]==401,"Unexpected anonymous workspace response "+str(anon)
        check("Account isolation","Anonymous workspace denied (401); authenticated mobile sync not tested")
        # Use Android settings to rotate the real emulator, not a resized desktop.
        adb("shell","settings","put","system","accelerometer_rotation","0")
        adb("shell","settings","put","system","user_rotation","1")
        landscape=wait_for(lambda:(v if (v:=inspect(c))["view"][0]>v["view"][1] and v["ready"] else None),25,"Landscape rotation")
        geometry(landscape);report["landscape"]=landscape
        c.screenshot(out/"11-web-landscape.png");m.screenshot(dev,"11-mobile-landscape")
        check("Landscape chart layout",str(landscape["view"]))
        adb("shell","settings","put","system","user_rotation","0")
        portrait=wait_for(lambda:(v if (v:=inspect(c))["view"][0]<v["view"][1] and v["ready"] else None),25,"Portrait return")
        geometry(portrait);assert portrait["bars"]>=20
        c.screenshot(out/"12-web-portrait-return.png")
        check("Return to portrait","Chart and candles preserved")
        from mobile_final import final_suite, long_soak
        if os.environ.get("SAFESIZE_LONG_SOAK")=="1":
            long_soak(c, m, dev, adb, report, out, check, inspect, wait_for)
        else:
            from mobile_advanced import advanced_suite
            advanced_suite(c, report, out, check, wait_for, inspect, ready, tap_dom)
            final_suite(c, m, dev, adb, report, out, check, inspect, wait_for, geometry, tap_dom)
            from mobile_stability import stability_suite
            stability_suite(c, m, dev, adb, report, out, check, wait_for, inspect)
        # Verify JS errors, but don't confuse expected unauthenticated 401 with page crashes.
        c.js("true")
        report["javascript_errors"]=c.errors;report["network"]=c.network
        assert not c.errors,"Unhandled JS errors: "+str(c.errors[:3])
        check("JavaScript exceptions","No uncaught exceptions observed after reload")
        report["web"]="passed: real anonymous Chrome chart, touch, layout, drawing, dialogs, rotation; auth and push not tested"
    finally:
        if c:
            try:
                report["last_web_state"]=inspect(c)
                c.screenshot(out/"web-final.png")
            except Exception as diagnostic_error:
                report["diagnostic_error"]=str(diagnostic_error)[:600]
            report["javascript_errors"]=c.errors;report["network"]=c.network
            c.close()
        try: adb("shell","settings","put","system","user_rotation","0")
        except Exception: pass
        try: adb("forward","--remove","tcp:9222")
        except Exception: pass
