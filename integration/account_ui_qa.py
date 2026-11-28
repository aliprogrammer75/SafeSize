"""Isolated end-to-end UI checks; invoked only after dedicated account API QA.
QA marker is local synthetic fixture, not user data. Read-only API assertions
and guarded restoration are used independently of the product UI.
"""
import json, time, uuid
import websocket
from playwright.sync_api import Error as PlaywrightError
from mobile_web import wait_for, tap_dom

READY="""(()=>!!(typeof initialized!=='undefined'&&initialized&&typeof chart?.getDataList==='function'&&chart.getDataList().length>=20&&document.querySelector('#ss-auto33-chip')&&document.querySelector('#ss-tools26')))()"""
KEY="tool:QaProbe"
RESUME="safesize:replay:resume:v1"

class UI:
    def __init__(self,page=None,cdp=None):
        self.page=page;self.cdp=cdp
    def js(self,expr):
        return self.page.evaluate(expr) if self.page else self.cdp.js(expr)
    def read(self,expr):
        # A reload after applying account settings legitimately destroys the JS context.
        # Retry read-only probes, never repeat a mutation or a click automatically.
        for attempt in range(12):
            try:return self.js(expr)
            except (PlaywrightError,RuntimeError) as e:
                if not any(x in str(e).lower() for x in ("execution context was destroyed","cannot find context","cannot find default execution context","inspected target navigated","context was destroyed")):raise
                time.sleep(.5)
        raise RuntimeError("UI read context unavailable after navigation")
    def ready(self):
        wait_for(lambda:self.read(READY),90,"UI chart ready")
    def mobile_point(self,selector):
        # The horizontally scrolling footer needs a settled layout and a genuine
        # hit target; never force-click an offscreen or covered control.
        self.cdp.call("Page.bringToFront")
        expr="""(s=>{const e=document.querySelector(s),body=document.body;const info={viewport:[innerWidth,innerHeight],body:[body.offsetWidth,body.offsetHeight],foreground:!document.hidden,found:!!e};if(!e)return info;e.scrollIntoView({behavior:'instant',block:'nearest',inline:'center'});const r=e.getBoundingClientRect(),x=r.x+r.width/2,y=r.y+r.height/2;const hit=document.elementFromPoint(x,y);return {...info,rect:[Math.round(r.x),Math.round(r.y),Math.round(r.width),Math.round(r.height)],x,y,hit:!!hit&&e.contains(hit),disabled:!!e.disabled,hidden:!!e.hidden}})("""
        last={}
        until=time.monotonic()+20
        while time.monotonic()<until:
            last=self.read(expr+json.dumps(selector)+")")
            if last.get("foreground") and last.get("hit") and not last.get("disabled") and not last.get("hidden"):
                return last
            time.sleep(.5)
        # Only numeric geometry and Boolean flags; no account or DOM content.
        print("UI CONTROL GEOMETRY:",selector,last,flush=True)
        raise RuntimeError("Control not visible or blocked after settling")
    def sel(self,selector):
        if self.page:self.page.locator(selector).click(timeout=15000)
        else:
            p=self.mobile_point(selector)
            self.cdp.tap(p["x"],p["y"])
    def button(self,label,confirm=False):
        if self.page:
            if confirm:self.page.once("dialog",lambda d:d.accept())
            self.page.locator("dialog[open]").get_by_role("button",name=label,exact=True).click(timeout=15000)
        else:
            # Identify the exact visible dialog button; tap it for ordinary
            # actions. For blocking browser confirmations dispatch the UI
            # click asynchronously and answer the native JS dialog via CDP.
            expr="""(label=>{const e=[...document.querySelectorAll('dialog[open] button')].find(e=>e.textContent.trim()===label&&!e.disabled&&!e.hidden);if(!e)return false;e.scrollIntoView({block:'nearest'});e.setAttribute('data-qa-target','1');return true})("""
            assert self.js(expr+json.dumps(label)+")"),"UI button unavailable"
            try:
                if confirm:
                    self.mobile_point("[data-qa-target]")
                    self.cdp.dialogs.clear()
                    self.js("setTimeout(()=>document.querySelector('[data-qa-target]').click(),150);true")
                    # Replay confirmation is displayed only after a server GET;
                    # wait for the actual dialog event rather than a fixed delay.
                    end=time.monotonic()+25
                    ws=self.cdp.ws
                    previous=ws.gettimeout()
                    try:
                        ws.settimeout(1)
                        while time.monotonic()<end and not self.cdp.dialogs:
                            try:ev=json.loads(ws.recv())
                            except (websocket.WebSocketTimeoutException,TimeoutError):continue
                            if ev.get("method")=="Page.javascriptDialogOpening":
                                self.cdp.dialogs.append({"type":ev.get("params",{}).get("type")})
                        assert self.cdp.dialogs and self.cdp.dialogs[-1]["type"]=="confirm","Expected UI confirmation not opened"
                    finally:ws.settimeout(previous)
                    self.cdp.call("Page.handleJavaScriptDialog",{"accept":True})
                else:
                    # Verify that a real touchscreen tap reached the intended control.
                    # A scroll gesture may consume a tap; retry once only if no click fired.
                    self.js("window.__qaClick=false;document.querySelector('[data-qa-target]').addEventListener('click',()=>window.__qaClick=true,{once:true,capture:true});true")
                    self.sel("[data-qa-target]")
                    time.sleep(.35)
                    clicked=self.read("window.__qaClick===true || !document.querySelector('[data-qa-target]')")
                    if not clicked:
                        print("UI TOUCH: retry after missing click",flush=True)
                        self.sel("[data-qa-target]")
            finally:
                try:self.js("document.querySelector('[data-qa-target]')?.removeAttribute('data-qa-target');true")
                except Exception:pass
    def close(self):
        if self.read("!!document.querySelector('dialog[open]')"):
            self.button("بستن")
    def sync_dialog(self):
        if self.read("!!document.querySelector('dialog[open] #ss-auto33-state')"):return
        self.close();self.sel("#ss-auto33-chip")
        wait_for(lambda:self.read("!!document.querySelector('dialog[open] #ss-auto33-state')"),10,"Sync dialog opened")
    def replay_dialog(self):
        self.close();self.sel("#ss-tools26");self.button("انتقال جلسه Replay")
    def marker(self):
        return self.read("JSON.parse(localStorage.getItem('tool:QaProbe')||'null')")
    def fixture(self,value):
        self.js("localStorage.setItem('tool:QaProbe',"+json.dumps(json.dumps(value))+");true")
    def state(self):
        return self.read("document.querySelector('#ss-auto33-state')?.textContent||document.querySelector('#ss-auto33-chip')?.title||''")
    def sync_probe(self,expected):
        # Only Boolean values and classified status; never log raw account data.
        state=self.state()
        kind=next((v for k,v in [
            ("انتخاب نسخه لازم","conflict"),("آماده اعمال","pending"),
            ("تغییر حساب آماده","busy_edit"),("همگام با حساب","synced"),
            ("غیرفعال","disabled"),("وارد حساب شوید","login_required"),
            ("متوقف موقت","temporary_error"),("نسخه حساب تغییر کرد","revision_changed"),
            ("همگام‌سازی خودکار فعال است","enabled")
        ] if k in state),"other")
        facts=self.read("""(()=>{const c=JSON.parse(localStorage.getItem('safesize:autosync:config:v1')||'{}');return {enabled:c.enabled===true,foreground:!document.hidden,dialog:!!document.querySelector('dialog[open] #ss-auto33-state'),applyVisible:!!document.querySelector('#ss-auto33-apply:not([hidden])'),base:!!localStorage.getItem('safesize:autosync:base:v1'),markerPresent:!!localStorage.getItem('tool:QaProbe')}})()""")
        return {**facts,"state":kind,"markerMatches":self.marker()==expected}
    def wait_marker(self,expected,seconds=150):
        last_log=0;last_action=0;last=None
        def step():
            nonlocal last_log,last_action,last
            self.ready()
            probe=self.sync_probe(expected)
            now=time.monotonic()
            if probe!=last or now-last_log>=15:
                print("UI SYNC STATE:", "desktop" if self.page else "Android",probe,flush=True)
                last=probe;last_log=now
            if probe["markerMatches"] and probe["enabled"]:return True
            if now-last_action>=5:
                if probe["state"]=="conflict":
                    self.sync_dialog()
                    print("UI SYNC ACTION: choose account conflict version",flush=True)
                    self.button("رفع تداخل: فقط بخش‌های متداخل از حساب",True)
                    last_action=now
                elif probe["state"]=="pending":
                    self.sync_dialog()
                    print("UI SYNC ACTION: apply incoming changes",flush=True)
                    self.button("اعمال تغییرات دریافتی")
                    last_action=now
            return False
        wait_for(step,seconds,"UI incoming QA marker")
        self.close()
    def enable(self,expected):
        self.ready();self.sync_dialog()
        print("UI SYNC ACTION: enable", "desktop" if self.page else "Android",flush=True)
        self.button("فعال‌سازی خودکار",True)
        # Do not wait for a periodic sync if the opt-in itself did not take effect.
        # A second explicit enable is safe only while config remains disabled.
        deadline=time.monotonic()+25; repeated=False
        while time.monotonic()<deadline:
            probe=self.sync_probe(expected)
            if probe["enabled"]:
                print("UI SYNC ENABLED:", "desktop" if self.page else "Android",probe,flush=True)
                break
            if probe["state"] in ("temporary_error","login_required"):
                print("UI SYNC ENABLE FAILURE:",probe,flush=True)
                raise RuntimeError("Sync activation server state: "+probe["state"])
            if not repeated and deadline-time.monotonic()<10:
                self.sync_dialog()
                print("UI SYNC ACTION: retry explicit opt-in while disabled",flush=True)
                self.button("فعال‌سازی خودکار",True)
                repeated=True
            time.sleep(1)
        else:
            print("UI SYNC ENABLE FAILURE:",self.sync_probe(expected),flush=True)
            raise RuntimeError("Sync opt-in not enabled")
        self.wait_marker(expected)
    def disable(self):
        self.ready();self.sync_dialog();self.button("غیرفعال‌سازی");self.close()
    def bookmark(self):
        return self.read("JSON.parse(localStorage.getItem("+json.dumps(RESUME)+")||'null')")
    def replay_state(self):
        return self.read("typeof replay!=='undefined'&&replay?{stamp:replay.bars[replay.index].timestamp,last:chart.getDataList().at(-1)?.timestamp}:null")

def run_ui(page,c,request,ok,revision,safe_workspace,check):
    d=UI(page=page);a=UI(cdp=c)
    get=lambda route:ok(request(d.read,route),"QA read")
    initial=get("workspace=1");safe_workspace(initial)
    prev=get("transfer=replay")
    assert prev["payload"] and prev["payload"]["session"].startswith("qa-"),"Expected synthetic API bookmark"
    original=initial["snapshot"]["entries"][KEY]
    assert original["owner"]=="safesize-qa-ci"
    own_bookmark=None
    stage="bring independent browsers to foreground"
    def checkpoint(name):
        nonlocal stage
        stage=name
        print("UI STAGE:",stage,flush=True)
    try:
        checkpoint(stage)
        page.bring_to_front()
        c.call("Page.bringToFront")
        d.ready();a.ready()
        checkpoint("desktop enable and initial account application")
        d.enable(original)
        checkpoint("Android enable and initial account application")
        a.enable(original)
        one={"owner":"safesize-qa-ci","marker":"qa-ui-desktop-"+uuid.uuid4().hex}
        two={"owner":"safesize-qa-ci","marker":"qa-ui-android-"+uuid.uuid4().hex}
        checkpoint("desktop fixture and timer upload")
        d.fixture(one)
        # No manual send: exercise actual foreground interval and server read.
        wait_for(lambda:get("workspace=1")["snapshot"]["entries"].get(KEY)==one,140,"Desktop automatic upload")
        checkpoint("Android receive and apply desktop change")
        a.wait_marker(one,150)
        check("Actual foreground UI autosync desktop to Android",{"timer_upload":True,"incoming_apply":True,"synthetic_fixture":True})
        checkpoint("Android fixture and timer upload")
        a.fixture(two)
        wait_for(lambda:get("workspace=1")["snapshot"]["entries"].get(KEY)==two,140,"Android automatic upload")
        checkpoint("desktop receive and apply Android change")
        d.wait_marker(two,150)
        check("Actual foreground UI autosync Android to desktop",{"timer_upload":True,"incoming_apply":True,"synthetic_fixture":True})
        # Stop sync before replay so no reload can interrupt either session.
        checkpoint("disable sync before Replay")
        d.disable();a.disable()
        d.ready();a.ready()
        checkpoint("desktop Replay start")
        stamp=d.js("chart.getDataList()[Math.max(1,chart.getDataList().length-30)].timestamp")
        # The date form is the existing product entry point, not a server POST.
        d.js("openDate('replay');document.getElementById('chart-date').value=new Date("+str(stamp)+").toISOString().slice(0,16);document.getElementById('date-form').requestSubmit();true")
        first=wait_for(d.replay_state,80,"Desktop Replay start")
        d.sel("#replay-step")
        stepped=wait_for(lambda:(x if (x:=d.replay_state()) and x["stamp"]>first["stamp"] else None),20,"Desktop Replay next candle")
        assert stepped["last"]==stepped["stamp"]
        own_bookmark=d.bookmark()
        assert own_bookmark and own_bookmark["stamp"]==stepped["stamp"] and own_bookmark["session"]
        checkpoint("desktop Replay save through UI")
        d.replay_dialog();d.button("ذخیره موقعیت این دستگاه",True)
        saved=wait_for(lambda:(s if (s:=get("transfer=replay"))["payload"] and s["payload"]["session"]==own_bookmark["session"] else None),35,"UI Replay save")
        assert saved["payload"]["stamp"]==stepped["stamp"]
        d.close();d.sel("#replay-exit")
        checkpoint("Android Replay resume through UI")
        a.replay_dialog();a.button("ادامه نشانک حساب",True)
        resumed=wait_for(a.replay_state,90,"Android actual Replay resume")
        restored=a.bookmark()
        assert resumed["stamp"]==stepped["stamp"]==resumed["last"]
        assert restored["session"]==own_bookmark["session"] and restored["slots"]==own_bookmark["slots"]
        checkpoint("Android Replay next candle")
        a.sel("#replay-step")
        advanced=wait_for(lambda:(x if (x:=a.replay_state()) and x["stamp"]>resumed["stamp"] else None),20,"Android Replay next candle")
        assert advanced["last"]==advanced["stamp"]
        a.sel("#replay-exit")
        check("Actual Replay UI save and resume across devices",{"same_timestamp":True,"same_session":True,"same_slots":True,"next_candle":True,"no_future_candle_shown":True})
    except Exception as e:
        # Only classify known error patterns. Never output browser payloads, URLs,
        # local storage or authentication values into the public Actions log.
        msg=str(e).lower()
        kinds=[("execution context was destroyed","navigation context reset"),("strict mode violation","ambiguous locator"),("not visible","hidden control"),("outside of the viewport","offscreen control"),("intercepts pointer events","blocked control"),("no dialog is showing","confirmation not open"),("timeout","timeout"),("referenceerror","JS reference error"),("typeerror","JS type error"),("syntaxerror","JS syntax error")]
        reason=next((v for k,v in kinds if k in msg),type(e).__name__)
        print("UI FAILURE:",stage,reason,flush=True)
        raise RuntimeError("UI stage failed: "+stage+"; "+reason) from None
    finally:
        # Stop foreground synchronization before restoring known QA records.
        for device in (d,a):
            try:
                device.close()
                if device.replay_state():device.sel("#replay-exit")
                device.disable()
            except Exception:pass
        current=get("workspace=1")
        marker=(current.get("snapshot") or {}).get("entries",{}).get(KEY,{})
        assert current["accountScope"]==initial["accountScope"] and marker.get("owner")=="safesize-qa-ci","QA workspace restore guard"
        ok(request(page.evaluate,"workspace=1",{"revision":revision(current),"snapshot":initial["snapshot"]},current["nonce"]),"Restore QA workspace")
        cur=get("transfer=replay")
        assert cur["accountScope"]==prev["accountScope"]
        if own_bookmark and cur["payload"] and cur["payload"]["session"]==own_bookmark["session"]:
            ok(request(page.evaluate,"transfer=replay",{"revision":revision(cur),"payload":prev["payload"]},cur["nonce"]),"Restore QA Replay")
        elif cur["payload"]!=prev["payload"]:
            raise AssertionError("Unexpected Replay bookmark; refused restore")
        check("UI suite synthetic account cleanup",{"workspace_restored":True,"replay_restored":True})
