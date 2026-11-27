"""Final mobile layout and notification checks; optional two-hour endurance.
All device settings changes are confined to the ephemeral emulator."""
import json,time,math,traceback

def final_suite(c,m,dev,adb,report,out,check,inspect,wait_for,geometry,tap_dom):
    cases=[]
    report["final_cases"]=cases
    def run(name,fn):
        try:
            detail=fn()
            cases.append({"name":name,"status":"pass","detail":detail});check(name,str(detail))
        except Exception as e:
            cases.append({"name":name,"status":"fail","detail":str(e)[:700]})
            print("FAIL:",name,str(e),flush=True);traceback.print_exc()
            try:c.screenshot(out/("final-failure-%02d.png"%len(cases)))
            except Exception:pass
    def screen_size():
        result=[]
        before=c.js("JSON.stringify(drawings.map(d=>({id:d.id,points:d.points})))")
        try:
            for w,h in [(320,640),(360,800),(480,900)]:
                adb("shell","wm","size",str(w*2)+"x"+str(h*2))
                adb("shell","wm","density","320")
                def valid():
                    s=inspect(c)
                    if not s["ready"] or abs(s["view"][0]-w)>8:return None
                    try:geometry(s)
                    except AssertionError:return None
                    return s
                s=wait_for(valid,35,"Automatic resize "+str(w))
                assert c.js("JSON.stringify(drawings.map(d=>({id:d.id,points:d.points})))")==before
                for selector in ("#ss-auto33-chip","#ss-tools26"):
                    tap_dom(c,selector)
                    d=wait_for(lambda:c.js("(()=>{const d=document.querySelector('dialog[open]');if(!d)return null;const r=d.getBoundingClientRect();return {x:r.x,y:r.y,right:r.right,bottom:r.bottom,w:innerWidth,h:innerHeight}})()"),8,selector)
                    assert d["x"]>=-2 and d["y"]>=-2 and d["right"]<=d["w"]+2 and d["bottom"]<=d["h"]+2,str(d)
                    c.js("document.querySelector('dialog[open]').close();true")
                c.screenshot(out/("final-width-"+str(w)+".png"))
                result.append({"requested":w,"viewport":s["view"],"plot":s["plot"]})
            return result
        finally:
            try:c.js("document.querySelector('dialog[open]')?.close();true")
            except Exception:pass
            adb("shell","wm","size","reset");adb("shell","wm","density","reset")
            time.sleep(2)
    run("Real Android screen size matrix 320/360/480",screen_size)
    def notification_shade():
        # No real account or actual VAPID subscription. Test the actual worker's
        # push event via DevTools and require visible Android notification evidence.
        adb("shell","pm","grant","com.android.chrome","android.permission.POST_NOTIFICATIONS")
        c.call("Browser.grantPermissions",{"origin":"https://alifatemii.ir","permissions":["notifications"]})
        sw=c.js("""(async()=>{const r=await navigator.serviceWorker.register('/safesize/?sw=1',{scope:'/safesize/',updateViaCache:'none'});const a=r.active?r:await navigator.serviceWorker.ready;return {scope:a.scope,active:!!a.active}})()""")
        assert sw["active"] and sw["scope"]=="https://alifatemii.ir/safesize/"
        c.call("ServiceWorker.enable")
        def registration():
            c.call("Runtime.evaluate",{"expression":"true"})
            return next((r["registrationId"] for r in c.registrations.values() if r.get("scopeURL")==sw["scope"] and not r.get("isDeleted")),None)
        reg=wait_for(registration,15,"Worker registration id")
        try:
            adb("shell","input","keyevent","KEYCODE_HOME")
            time.sleep(2)
            c.call("ServiceWorker.deliverPushMessage",{"origin":"https://alifatemii.ir","registrationId":reg,"data":json.dumps({"title":"SafeSize QA push","body":"Emulator push event","tag":"ss-qa-push"})})
            time.sleep(3)
            adb("shell","cmd","statusbar","expand-notifications")
            time.sleep(2)
            s=m.text("mobile_list_elements_on_screen",device=dev,format="text")
            (out/"final-notification-shade.txt").write_text(s,encoding="utf-8")
            m.screenshot(dev,"final-notification-shade")
            assert "SafeSize QA push" in s or "Emulator push event" in s,"Notification not found in Android shade"
            return {"android_shade_visible":True,"event":"CDP simulated push, real worker","external_delivery":False}
        finally:
            try:adb("shell","cmd","statusbar","collapse")
            except Exception:pass
            try:m.tool("mobile_launch_app",device=dev,packageName="com.android.chrome");c.call("Page.bringToFront")
            except Exception:pass
            try:c.js("(async()=>{const r=await navigator.serviceWorker.getRegistration('/safesize/');for(const n of await r.getNotifications({tag:'ss-qa-push'}))n.close();return true})()")
            except Exception:pass
    run("Android notification shade from actual worker push event",notification_shade)
    failed=[x for x in cases if x["status"]=="fail"]
    report["final_summary"]={"passed":len(cases)-len(failed),"failed":len(failed)}
    if failed:raise RuntimeError("Final checks failed: "+json.dumps(failed,ensure_ascii=False)[:1800])

def long_soak(c,m,dev,adb,report,out,check,inspect,wait_for):
    c.call("Performance.enable")
    adb("shell","svc","power","stayon","true")
    base=c.js("JSON.stringify(drawings.map(d=>({id:d.id,points:d.points})))")
    samples=[];start=time.monotonic()
    report["long_soak_samples"]=samples
    for i in range(121):
        if i:time.sleep(max(0,start+i*60-time.monotonic()))
        s=inspect(c)
        metrics={v["name"]:v["value"] for v in c.call("Performance.getMetrics")["metrics"]}
        details=c.js("({hidden:document.hidden,gaps:candleGaps(chart.getDataList(),market.interval).length,rest:readMarket.ssRest31(),ws:readMarket.ssPerf21(),drawings:JSON.stringify(drawings.map(d=>({id:d.id,points:d.points})))})")
        sample={"elapsed":round(time.monotonic()-start),"heap":metrics.get("JSHeapUsedSize"),"nodes":metrics.get("Nodes"),"bars":s["bars"],"last":s["last"],"gaps":details["gaps"],"hidden":details["hidden"],"rest_success":details["rest"]["success"],"ws_received":details["ws"]["received"],"status":details["ws"]["state"],"errors":len(c.errors)}
        samples.append(sample)
        (out/"endurance-samples.json").write_text(json.dumps(samples,indent=2),encoding="utf-8")
        print("SOAK",json.dumps(sample),flush=True)
        assert s["ready"] and s["bars"]>=20 and details["drawings"]==base,"Chart state lost"
        assert not details["hidden"],"Chrome not foreground"
        assert not c.errors,"Uncaught JS error"
        if i and i%15==0:c.screenshot(out/("endurance-%03d.png"%i))
    assert samples[-1]["elapsed"]>=7200
    assert samples[-1]["rest_success"]>samples[0]["rest_success"]
    assert samples[-1]["last"]["timestamp"]>samples[0]["last"]["timestamp"],"No new candles over two hours"
    heap=[s["heap"] for s in samples if s["heap"] is not None]
    assert heap and max(heap)<max(heap[0]*3,heap[0]+64*1048576),"Excessive heap growth"
    check("Two hour Android endurance",str({"seconds":samples[-1]["elapsed"],"samples":len(samples),"heap_peak":max(heap),"market_calls":samples[-1]["rest_success"]-samples[0]["rest_success"]}))
