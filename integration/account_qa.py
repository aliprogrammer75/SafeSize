"""Explicit opt-in QA: a dedicated WordPress subscriber, two fresh browser sessions.
Never run with a real customer/admin account. No screenshots or credential logs.
Only synthetic workspace, replay and encrypted journal records are written."""
import os, sys, json, time, uuid, pathlib, subprocess, re, xml.etree.ElementTree as ET
import websocket
from playwright.sync_api import sync_playwright
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]/"qa"))
from mobile_web import CDP, targets, wait_for

ORIGIN="https://alifatemii.ir"
SITE=ORIGIN+"/safesize/"
LOGIN=ORIGIN+"/wp-login.php?redirect_to=%2Fsafesize%2F"
ACCOUNT="safesize-qa-ci"
PASSWORD=os.environ.get("SAFESIZE_QA_PASSWORD","")
OUT=pathlib.Path("account-qa-output");OUT.mkdir(exist_ok=True)
REPORT={"account":ACCOUNT,"scope":"dedicated synthetic test account only","checks":[]}
def save():
    (OUT/"report.json").write_text(json.dumps(REPORT,ensure_ascii=False,indent=2),encoding="utf-8")
def check(name,detail):
    REPORT["checks"].append({"name":name,"status":"pass","detail":detail});save()
    print("PASS:",name,detail,flush=True)
def fail(name,detail):
    REPORT["checks"].append({"name":name,"status":"fail","detail":detail});save()
    print("FAIL:",name,detail,flush=True)
def adb(*args):
    return subprocess.check_output(["adb",*args],timeout=45,stderr=subprocess.STDOUT)
def screen():
    adb("shell","uiautomator","dump","/sdcard/qa-window.xml")
    return ET.fromstring(adb("shell","cat","/sdcard/qa-window.xml"))
def chrome():
    # Do not require the live website to load in order to discover Chrome.
    # Start a local blank tab, complete ordinary onboarding, then navigate
    # the resulting page target to the dedicated account login.
    adb("shell","am","start","-a","android.intent.action.VIEW","-d","about:blank","-p","com.android.chrome")
    adb("forward","tcp:9222","localabstract:chrome_devtools_remote")
    end=time.monotonic()+150
    launch_at=time.monotonic()
    last_diag=0
    while time.monotonic()<end:
        try:
            nodes=list(screen().iter("node"))
        except (ET.ParseError,subprocess.SubprocessError):
            nodes=[]
        # Only standard Chrome first-run choices; never click a site prompt.
        allowed=("use without an account","continue without an account","no thanks","not now","got it","accept & continue")
        node=next((n for n in nodes if n.get("package")=="com.android.chrome" and ((n.get("text") or "").strip().lower() in allowed or (n.get("content-desc") or "").strip().lower() in allowed)),None)
        if node is not None:
            nums=list(map(int,re.findall(r"\d+",node.get("bounds",""))))
            if len(nums)==4:
                adb("shell","input","tap",str((nums[0]+nums[2])//2),str((nums[1]+nums[3])//2))
                print("CHROME STARTUP: standard onboarding action",flush=True)
                time.sleep(3)
                continue
        try:
            pages=[t for t in targets() if t.get("type")=="page" and t.get("webSocketDebuggerUrl") and (t.get("url","").startswith(("about:blank","chrome://newtab","https://alifatemii.ir/","chrome-native://newtab")) or t.get("url","")=="" )]
        except (OSError,ValueError):
            pages=[]
        if pages:
            c=None
            try:
                c=CDP(pages[0]["webSocketDebuggerUrl"])
                c.ws.settimeout(45)
                c.call("Page.enable");c.call("Runtime.enable")
                c.call("Page.bringToFront")
                print("CHROME STARTUP: independent page target attached",flush=True)
                return c
            except (RuntimeError,OSError,websocket.WebSocketException):
                if c:
                    try:c.close()
                    except Exception:pass
        if time.monotonic()-last_diag>=15:
            print("CHROME STARTUP:",{"page_targets":len(pages),"chrome_nodes":sum(n.get("package")=="com.android.chrome" for n in nodes)},flush=True)
            last_diag=time.monotonic()
        if time.monotonic()-launch_at>25:
            adb("shell","am","start","-a","android.intent.action.VIEW","-d","about:blank","-p","com.android.chrome")
            adb("forward","tcp:9222","localabstract:chrome_devtools_remote")
            launch_at=time.monotonic()
        time.sleep(3)
    raise RuntimeError("Chrome page target not ready")
def request(evaluate,route,body=None,nonce=None):
    args=json.dumps({"route":route,"body":body,"nonce":nonce},ensure_ascii=False)
    # Finite browser network deadline; retry only reads. A timed-out POST may
    # already have committed, so it is never automatically submitted again.
    expression="(async a=>{try{const r=await fetch('/safesize/?'+a.route,{signal:AbortSignal.timeout(23000),cache:'no-store',credentials:'same-origin',...(a.body?{method:'POST',headers:{'Content-Type':'application/json','X-SafeSize-Nonce':a.nonce||''},body:JSON.stringify(a.body)}:{})});let d={};try{d=await r.json()}catch(_){}return {status:r.status,data:d}}catch(e){return {status:0,transport:e.name}}})("+args+")"
    for attempt in range(3 if body is None else 1):
        try:
            result=evaluate(expression)
            if not isinstance(result,dict):raise RuntimeError("Missing API result")
            if result.get("status")==0:
                if body is None and attempt<2:
                    print("QA READ RETRY:",route.split("&")[0],attempt+1,flush=True)
                    time.sleep(2);continue
                raise TimeoutError("Browser API transport failed")
            return result
        except (websocket.WebSocketTimeoutException,TimeoutError):
            if body is not None or attempt==2:raise
            print("QA READ RETRY:",route.split("&")[0],attempt+1,flush=True)
            time.sleep(2)
def ok(r,label):
    assert r["status"]==200,label+" HTTP "+str(r["status"])
    return r["data"]
def revision(r):
    assert isinstance(r.get("revision"),str) and isinstance(r.get("nonce"),str) and r["nonce"]
    return r["revision"]
def safe_workspace(r):
    s=r.get("snapshot")
    assert s is None or (set(s.get("entries",{}))=={"tool:QaProbe"} and s["entries"]["tool:QaProbe"].get("owner")==ACCOUNT),"Refusing to replace non-QA workspace"
def workspace(e1,e2):
    print("WORKSPACE STAGE: desktop initial read",flush=True)
    a=ok(request(e1,"workspace=1"),"Desktop workspace")
    print("WORKSPACE STAGE: Android initial read",flush=True)
    b=ok(request(e2,"workspace=1"),"Android workspace")
    assert a.get("accountScope") and a["accountScope"]==b.get("accountScope")
    safe_workspace(a);safe_workspace(b);old=revision(a)
    tag="qa-"+uuid.uuid4().hex
    def snapshot(v):
        return {"entries":{"tool:QaProbe":{"owner":ACCOUNT,"marker":v}},"preferences":{"axis":"normal","zone":"Etc/UTC","favorites":["BTCUSDT"]}}
    one=snapshot(tag)
    print("WORKSPACE STAGE: desktop write",flush=True)
    saved=ok(request(e1,"workspace=1",{"revision":old,"snapshot":one},a["nonce"]),"Desktop save")
    print("WORKSPACE STAGE: Android read of desktop write",flush=True)
    read=ok(request(e2,"workspace=1"),"Android read")
    assert read["snapshot"]==one and read["revision"]==saved["revision"]!=old
    two=snapshot(tag+"-android")
    print("WORKSPACE STAGE: Android write",flush=True)
    saved2=ok(request(e2,"workspace=1",{"revision":revision(read),"snapshot":two},read["nonce"]),"Android save")
    print("WORKSPACE STAGE: desktop read of Android write",flush=True)
    assert ok(request(e1,"workspace=1"),"Desktop read")["snapshot"]==two
    print("WORKSPACE STAGE: stale revision rejection",flush=True)
    stale=request(e1,"workspace=1",{"revision":old,"snapshot":one},a["nonce"])
    assert stale["status"]==409,"Stale revision not rejected"
    print("WORKSPACE STAGE: invalid nonce rejection",flush=True)
    invalid=request(e2,"workspace=1",{"revision":saved2["revision"],"snapshot":one},"invalid-qa-nonce")
    assert invalid["status"]==403,"Invalid nonce not rejected"
    print("WORKSPACE STAGE: final verification",flush=True)
    assert ok(request(e1,"workspace=1"),"Read after rejection")["snapshot"]==two
    check("Two authenticated browsers workspace round trip",{"desktop_to_android":True,"android_to_desktop":True,"stale":409,"nonce":403})
def replay_transfer(e1,e2):
    a=ok(request(e1,"transfer=replay"),"Replay GET");b=ok(request(e2,"transfer=replay"),"Replay Android GET")
    assert a["accountScope"]==b["accountScope"]
    for r in (a,b):
        assert r["payload"] is None or r["payload"].get("session","").startswith("qa-"),"Refusing to replace non-QA Replay"
    p={"v":1,"stamp":int(time.time()//900*900000)-3600000,"slots":["0|BTCUSDT|15m|LAST_PRICE"],"session":"qa-"+uuid.uuid4().hex}
    saved=ok(request(e1,"transfer=replay",{"revision":revision(a),"payload":p},a["nonce"]),"Replay save")
    read=ok(request(e2,"transfer=replay"),"Replay read")
    assert read["payload"]==p and read["revision"]==saved["revision"]
    p2={**p,"stamp":p["stamp"]+900000}
    ok(request(e2,"transfer=replay",{"revision":revision(read),"payload":p2},read["nonce"]),"Replay return")
    assert ok(request(e1,"transfer=replay"),"Replay return read")["payload"]==p2
    check("Replay bookmark transport",{"same_session":True,"round_trip":True,"actual_resume_ui":"not covered here"})
# Synthetic QA-only passphrase, never a user journal passphrase.
CRYPTO="""(async a=>{const enc=new TextEncoder(),dec=new TextDecoder(),base=await crypto.subtle.importKey('raw',enc.encode('SafeSize synthetic QA only v1'),{name:'PBKDF2'},false,['deriveKey']);const b64=x=>btoa(String.fromCharCode(...x)),bytes=s=>Uint8Array.from(atob(s),c=>c.charCodeAt(0));let salt,iv;if(a.mode==='encrypt'){salt=crypto.getRandomValues(new Uint8Array(16));iv=crypto.getRandomValues(new Uint8Array(12))}else{salt=bytes(a.payload.salt);iv=bytes(a.payload.iv)}const key=await crypto.subtle.deriveKey({name:'PBKDF2',salt,iterations:600000,hash:'SHA-256'},base,{name:'AES-GCM',length:256},false,['encrypt','decrypt']);const opts={name:'AES-GCM',iv,additionalData:enc.encode('SafeSize-Paper-v1')};if(a.mode==='encrypt'){const cipher=new Uint8Array(await crypto.subtle.encrypt(opts,key,enc.encode(JSON.stringify({v:1,rows:a.rows}))));return {v:1,alg:'AES-256-GCM/PBKDF2-SHA256',iterations:600000,salt:b64(salt),iv:b64(iv),cipher:b64(cipher)}}const plain=await crypto.subtle.decrypt(opts,key,bytes(a.payload.cipher));return JSON.parse(dec.decode(plain)).rows})"""
def crypt(e,mode,**kw):
    return e(CRYPTO+"("+json.dumps({"mode":mode,**kw})+")")
def journal(e1,e2):
    a=ok(request(e1,"transfer=paper"),"Journal GET")
    b=ok(request(e2,"transfer=paper"),"Journal Android GET")
    assert a["accountScope"]==b["accountScope"]
    for r in (a,b):
        if r["payload"] is not None:
            rows=crypt(e1,"decrypt",payload=r["payload"])
            assert all(x.get("id","").startswith("qa-") for x in rows),"Refusing to replace non-QA journal"
    row={"id":"qa-"+uuid.uuid4().hex,"symbol":"BTCUSDT","side":"long","average":100,"qty":1,"remaining":1,"risk":5,"stop":95,"target":110,"fee":0,"created":int(time.time()*1000),"exits":[]}
    payload=crypt(e1,"encrypt",rows=[row])
    assert "BTCUSDT" not in json.dumps(payload)
    save1=ok(request(e1,"transfer=paper",{"revision":revision(a),"payload":payload},a["nonce"]),"Encrypted journal save")
    read=ok(request(e2,"transfer=paper"),"Encrypted journal read")
    assert read["revision"]==save1["revision"] and crypt(e2,"decrypt",payload=read["payload"])==[row]
    # Modify only the synthetic record on the other device.
    changed={**row,"target":115}
    payload2=crypt(e2,"encrypt",rows=[changed])
    ok(request(e2,"transfer=paper",{"revision":revision(read),"payload":payload2},read["nonce"]),"Encrypted journal return")
    assert crypt(e1,"decrypt",payload=ok(request(e1,"transfer=paper"),"Encrypted journal return read")["payload"])==[changed]
    check("Encrypted synthetic journal transport",{"desktop_to_android":True,"android_to_desktop":True,"plaintext_not_sent":True})
def push(c,desktop):
    device="qa-"+uuid.uuid4().hex
    route="push=1&device="+device
    registered=False
    sub_created=False
    try:
        adb("shell","pm","grant","com.android.chrome","android.permission.POST_NOTIFICATIONS")
        c.call("Browser.grantPermissions",{"origin":ORIGIN,"permissions":["notifications"]})
        info=ok(request(c.js,route),"Push GET")
        assert not info.get("active") and info.get("publicKey") and info.get("nonce")
        c.ws.settimeout(40)
        args=json.dumps({"route":route,"device":device,"key":info["publicKey"],"nonce":info["nonce"]})
        # Real PushManager subscription; no DevTools synthetic push injection.
        result=c.call("Runtime.evaluate",{"expression":"""(async a=>{const timeout=(p,ms)=>Promise.race([p,new Promise((_,r)=>setTimeout(()=>r(Error('timeout')),ms))]);try{const r=await timeout(navigator.serviceWorker.register('/safesize/?sw=1',{scope:'/safesize/',updateViaCache:'none'}),10000),reg=r.active?r:await timeout(navigator.serviceWorker.ready,8000),k=a.key.replace(/-/g,'+').replace(/_/g,'/'),key=Uint8Array.from(atob(k.padEnd(Math.ceil(k.length/4)*4,'=')),c=>c.charCodeAt(0));const sub=await reg.pushManager.getSubscription()||await timeout(reg.pushManager.subscribe({userVisibleOnly:true,applicationServerKey:key}),15000);const response=await fetch('/safesize/?'+a.route,{method:'POST',headers:{'Content-Type':'application/json','X-SafeSize-Nonce':a.nonce},credentials:'same-origin',body:JSON.stringify({action:'subscribe',device:a.device,subscription:sub.toJSON(),alerts:[]})});let d={};try{d=await response.json()}catch(_){}return {sub:true,status:response.status,active:!!d.active}}catch(e){return {sub:false,error:e.name}}})("""+args+")","awaitPromise":True,"returnByValue":True,"userGesture":True})
        state=result.get("result",{}).get("value",{})
        sub_created=bool(state.get("sub"));registered=state.get("status")==200 and state.get("active")
        assert registered,"Real subscription/registration not confirmed: "+str({k:v for k,v in state.items() if k in ("sub","status","active","error")})
        adb("shell","input","keyevent","KEYCODE_HOME");time.sleep(2)
        sender=ok(request(desktop,route),"Independent desktop push status")
        sent=ok(request(desktop,route,{"action":"test","device":device},sender["nonce"]),"Actual server push test")
        assert sent.get("accepted") and 200<=sent.get("deliveryCode",0)<300
        check("Actual server accepted VAPID push",{"delivery_code":sent["deliveryCode"],"android_background":True})
        def received():
            return c.js("(async()=>{const r=await navigator.serviceWorker.getRegistration('/safesize/');return (await r.getNotifications({tag:'ss-test'})).map(n=>({tag:n.tag,title:n.title}))})()")
        notices=wait_for(received,45,"Actual server notification received")
        assert any(n["tag"]=="ss-test" and "SafeSize" in n["title"] for n in notices)
        adb("shell","cmd","statusbar","expand-notifications");time.sleep(2)
        node=screen()
        visible=any("SafeSize" in ((n.get("text") or "")+" "+(n.get("content-desc") or "")) and ("تست" in ((n.get("text") or "")+" "+(n.get("content-desc") or ""))) for n in node.iter("node"))
        assert visible,"Real notification not found in Android shade"
        check("Real server push received in Android notification shade",{"service_worker":True,"visible":True})
    finally:
        # Always remove only the randomized QA device. No other device touched.
        try:
            x=ok(request(desktop,route),"QA device cleanup GET")
            if x.get("active"):assert not ok(request(desktop,route,{"action":"unsubscribe","device":device},x["nonce"]),"QA device cleanup").get("active")
            REPORT["push_cleanup"]="server QA device inactive"
        except Exception:REPORT["push_cleanup"]="server cleanup unconfirmed"
        try:adb("shell","cmd","statusbar","collapse")
        except Exception:pass
        try:
            adb("shell","am","start","-a","android.intent.action.VIEW","-d",SITE,"-p","com.android.chrome")
            c.js("(async()=>{const r=await navigator.serviceWorker.getRegistration('/safesize/');if(r){for(const n of await r.getNotifications({tag:'ss-test'}))n.close();const s=await r.pushManager.getSubscription();if(s)await s.unsubscribe()}return true})()")
        except Exception:pass
        save()
def main():
    if not PASSWORD:raise RuntimeError("Dedicated QA password not configured in GitHub Actions secret")
    c=None
    with sync_playwright() as p:
        browser=p.chromium.launch(headless=True)
        context=browser.new_context()
        page=context.new_page()
        try:
            # Never print raw errors from login or save screenshots of credentials.
            stage="desktop login form"
            try:
                page.goto(LOGIN,wait_until="domcontentloaded",timeout=45000)
                page.locator("#user_login").fill(ACCOUNT)
                page.locator("#user_pass").fill(PASSWORD)
                stage="desktop login redirect"
                page.locator("#wp-submit").click(timeout=15000)
                page.wait_for_url("**/safesize/**",timeout=60000)
                print("READY: Desktop login redirect",flush=True)
                stage="Android Chrome startup"
                c=chrome()
                print("READY: Android Chrome startup",flush=True)
                stage="Android login form"
                c.call("Page.navigate",{"url":LOGIN})
                wait_for(lambda:c.js("!!document.getElementById('user_login')&&!!document.getElementById('user_pass')"),60,"Android login form")
                print("READY: Android login form",flush=True)
                stage="Android login redirect"
                c.js("(a=>{document.getElementById('user_login').value=a.u;document.getElementById('user_pass').value=a.p;setTimeout(()=>document.getElementById('wp-submit').click(),100);return true})("+json.dumps({"u":ACCOUNT,"p":PASSWORD})+")")
                wait_for(lambda:c.js("location.pathname.replace(/\\/$/,'')==='/safesize'&&document.readyState!=='loading'"),75,"Android login redirect")
                print("READY: Android login redirect",flush=True)
            except Exception as e:
                fail("Authentication stage",{"stage":stage,"error_type":type(e).__name__})
                raise RuntimeError("Dedicated QA login failed; no authentication details logged") from None
            desktop=page.evaluate
            # Every mutation is gated on independent authentication, matching account scope
            # and records that are empty or known synthetic QA records.
            a=ok(request(desktop,"workspace=1"),"Desktop authenticated")
            b=ok(request(c.js,"workspace=1"),"Android authenticated")
            assert a.get("accountScope") and a["accountScope"]==b.get("accountScope")
            safe_workspace(a);safe_workspace(b)
            check("Independent authenticated desktop and Android browser sessions",{"matching_scope":True})
            for name,fn in [("Workspace",lambda:workspace(desktop,c.js)),("Replay",lambda:replay_transfer(desktop,c.js)),("Encrypted journal",lambda:journal(desktop,c.js))]:
                try:fn()
                except Exception as e:
                    # Keep report safe: only exception class, no response body, cookie, or endpoint.
                    fail(name,type(e).__name__)
            if os.environ.get("SAFESIZE_UI_QA")=="1" and not any(x["status"]=="fail" for x in REPORT["checks"]):
                from account_ui_qa import run_ui
                try:run_ui(page,c,request,ok,revision,safe_workspace,check)
                except Exception as e:fail("Actual account UI suite",type(e).__name__)
            # Keep the push/background transition separate from foreground UI acceptance.
            try:push(c,desktop)
            except Exception as e:fail("Actual VAPID push",type(e).__name__)
            if any(x["status"]=="fail" for x in REPORT["checks"]):raise RuntimeError("One or more dedicated account cases failed")
        finally:
            if c:
                try:c.close()
                except Exception:pass
            context.close();browser.close()
            try:adb("forward","--remove","tcp:9222")
            except Exception:pass
            save()
if __name__=="__main__":
    try:main()
    except Exception as e:
        fail("Dedicated account suite",type(e).__name__)
        sys.exit(1)
