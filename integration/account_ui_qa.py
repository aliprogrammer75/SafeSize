"""Isolated end-to-end UI checks; invoked only after dedicated account API QA.
QA marker is local synthetic fixture, not user data. Read-only API assertions
and guarded restoration are used independently of the product UI.
"""
import json, time, uuid
from mobile_web import wait_for, tap_dom

READY="""(()=>!!(typeof initialized!=='undefined'&&initialized&&typeof chart?.getDataList==='function'&&chart.getDataList().length>=20&&document.querySelector('#ss-auto33-chip')&&document.querySelector('#ss-tools26')))()"""
KEY="tool:QaProbe"
RESUME="safesize:replay:resume:v1"

class UI:
    def __init__(self,page=None,cdp=None):
        self.page=page;self.cdp=cdp
    def js(self,expr):
        return self.page.evaluate(expr) if self.page else self.cdp.js(expr)
    def ready(self):
        wait_for(lambda:self.js(READY),90,"UI chart ready")
    def sel(self,selector):
        if self.page:self.page.locator(selector).click(timeout=15000)
        else:tap_dom(self.cdp,selector)
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
                    self.js("setTimeout(()=>document.querySelector('[data-qa-target]').click(),150);true")
                    time.sleep(.7)
                    self.cdp.call("Page.handleJavaScriptDialog",{"accept":True})
                else:tap_dom(self.cdp,"[data-qa-target]")
            finally:
                try:self.js("document.querySelector('[data-qa-target]')?.removeAttribute('data-qa-target');true")
                except Exception:pass
    def close(self):
        if self.js("!!document.querySelector('dialog[open]')"):
            self.button("بستن")
    def sync_dialog(self):
        self.close();self.sel("#ss-auto33-chip")
    def replay_dialog(self):
        self.close();self.sel("#ss-tools26");self.button("انتقال جلسه Replay")
    def marker(self):
        return self.js("JSON.parse(localStorage.getItem('tool:QaProbe')||'null')")
    def fixture(self,value):
        self.js("localStorage.setItem('tool:QaProbe',"+json.dumps(json.dumps(value))+");true")
    def state(self):
        return self.js("document.querySelector('#ss-auto33-state')?.textContent||document.querySelector('#ss-auto33-chip')?.title||''")
    def wait_marker(self,expected,seconds=100):
        def step():
            self.ready()
            if self.marker()==expected:return True
            msg=self.state()
            if "تداخل" in msg and "انتخاب نسخه لازم" in msg:
                self.sync_dialog();self.button("رفع تداخل: فقط بخش‌های متداخل از حساب",True)
            elif "آماده اعمال" in msg or "تغییر حساب آماده" in msg:
                self.sync_dialog();self.button("اعمال تغییرات دریافتی")
            return False
        wait_for(step,seconds,"UI incoming QA marker")
        self.close()
    def enable(self,expected):
        self.ready();self.sync_dialog();self.button("فعال‌سازی خودکار",True)
        self.wait_marker(expected)
        self.close()
        wait_for(lambda:self.js("JSON.parse(localStorage.getItem('safesize:autosync:config:v1')||'{}').enabled===true"),20,"Sync enabled")
    def disable(self):
        self.ready();self.sync_dialog();self.button("غیرفعال‌سازی");self.close()
    def bookmark(self):
        return self.js("JSON.parse(localStorage.getItem("+json.dumps(RESUME)+")||'null')")
    def replay_state(self):
        return self.js("typeof replay!=='undefined'&&replay?{stamp:replay.bars[replay.index].timestamp,last:chart.getDataList().at(-1)?.timestamp}:null")

def run_ui(page,c,request,ok,revision,safe_workspace,check):
    d=UI(page=page);a=UI(cdp=c)
    get=lambda route:ok(request(page.evaluate,route),"QA read")
    initial=get("workspace=1");safe_workspace(initial)
    prev=get("transfer=replay")
    assert prev["payload"] and prev["payload"]["session"].startswith("qa-"),"Expected synthetic API bookmark"
    original=initial["snapshot"]["entries"][KEY]
    assert original["owner"]=="safesize-qa-ci"
    own_bookmark=None
    try:
        d.enable(original);a.enable(original)
        one={"owner":"safesize-qa-ci","marker":"qa-ui-desktop-"+uuid.uuid4().hex}
        two={"owner":"safesize-qa-ci","marker":"qa-ui-android-"+uuid.uuid4().hex}
        d.fixture(one)
        # No manual send: exercise actual foreground interval and server read.
        wait_for(lambda:get("workspace=1")["snapshot"]["entries"].get(KEY)==one,140,"Desktop automatic upload")
        a.wait_marker(one,150)
        check("Actual foreground UI autosync desktop to Android",{"timer_upload":True,"incoming_apply":True,"synthetic_fixture":True})
        a.fixture(two)
        wait_for(lambda:get("workspace=1")["snapshot"]["entries"].get(KEY)==two,140,"Android automatic upload")
        d.wait_marker(two,150)
        check("Actual foreground UI autosync Android to desktop",{"timer_upload":True,"incoming_apply":True,"synthetic_fixture":True})
        # Stop sync before replay so no reload can interrupt either session.
        d.disable();a.disable()
        d.ready();a.ready()
        stamp=d.js("chart.getDataList()[Math.max(1,chart.getDataList().length-30)].timestamp")
        # The date form is the existing product entry point, not a server POST.
        d.js("openDate('replay');document.getElementById('chart-date').value=new Date("+str(stamp)+").toISOString().slice(0,16);document.getElementById('date-form').requestSubmit();true")
        first=wait_for(d.replay_state,80,"Desktop Replay start")
        d.sel("#replay-step")
        stepped=wait_for(lambda:(x if (x:=d.replay_state()) and x["stamp"]>first["stamp"] else None),20,"Desktop Replay next candle")
        assert stepped["last"]==stepped["stamp"]
        own_bookmark=d.bookmark()
        assert own_bookmark and own_bookmark["stamp"]==stepped["stamp"] and own_bookmark["session"]
        d.replay_dialog();d.button("ذخیره موقعیت این دستگاه",True)
        saved=wait_for(lambda:(s if (s:=get("transfer=replay"))["payload"] and s["payload"]["session"]==own_bookmark["session"] else None),35,"UI Replay save")
        assert saved["payload"]["stamp"]==stepped["stamp"]
        d.close();d.sel("#replay-exit")
        a.replay_dialog();a.button("ادامه نشانک حساب",True)
        resumed=wait_for(a.replay_state,90,"Android actual Replay resume")
        restored=a.bookmark()
        assert resumed["stamp"]==stepped["stamp"]==resumed["last"]
        assert restored["session"]==own_bookmark["session"] and restored["slots"]==own_bookmark["slots"]
        a.sel("#replay-step")
        advanced=wait_for(lambda:(x if (x:=a.replay_state()) and x["stamp"]>resumed["stamp"] else None),20,"Android Replay next candle")
        assert advanced["last"]==advanced["stamp"]
        a.sel("#replay-exit")
        check("Actual Replay UI save and resume across devices",{"same_timestamp":True,"same_session":True,"same_slots":True,"next_candle":True,"no_future_candle_shown":True})
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
