"""Network recovery, short soak, and anonymous notification checks on ephemeral Android.
No actual account credentials, cloud subscriptions or production mutations."""
import json, time, math, traceback

def stability_suite(c, m, dev, adb, report, out, check, wait_for, inspect):
    cases=[]
    report["stability"]=cases
    def run(name,fn):
        try:
            detail=fn()
            cases.append({"name":name,"status":"pass","detail":detail})
            check(name,str(detail))
        except Exception as e:
            cases.append({"name":name,"status":"fail","detail":str(e)[:800]})
            print("FAIL:",name,str(e),flush=True)
            traceback.print_exc()
            try:c.screenshot(out/("stability-fail-%02d.png"%len(cases)))
            except Exception:pass
    def state():
        return c.js("""(()=>({online:navigator.onLine,hidden:document.hidden,ready:initialized===true,bars:chart.getDataList().length,last:chart.getDataList().at(-1)?.timestamp,ordered:chart.getDataList().every((b,i,a)=>!i||a[i-1].timestamp<b.timestamp),gaps:candleGaps(chart.getDataList(),market.interval).length,drawings:drawings.map(d=>({id:d.id,points:d.points})),ws:readMarket.ssPerf21(),rest:readMarket.ssRest31(),status:document.getElementById('ss-net21')?.dataset.state,price:document.getElementById('last-price')?.textContent,now:Date.now()}))()""")
    def condition(offline=False,latency=0,down=-1,up=-1):
        c.call("Network.emulateNetworkConditions",{"offline":offline,"latency":latency,"downloadThroughput":down,"uploadThroughput":up})
    def fresh(s):
        ws=s["ws"];r=s["rest"]
        return s["online"] and s["ready"] and s["bars"]>=20 and s["ordered"] and s["status"] in ("live","rest") and r["lastSuccess"]>0 and s["now"]-r["lastSuccess"]<30000
    def offline_recovery():
        before=wait_for(lambda:(s if fresh(s:=state()) else None),45,"Fresh price before offline")
        report["network_before"]=before
        try:
            condition(offline=True)
            offline=wait_for(lambda:(s if not (s:=state())["online"] and s["status"]=="offline" else None),20,"Browser offline state")
            assert offline["bars"]>=20 and offline["drawings"]==before["drawings"]
            assert offline["ws"]["readyState"]==-1,"WebSocket still active"
            blocked=c.js("""readMarket('kline',{...market,limit:'3'}).then(()=>({blocked:false}),e=>({blocked:!!e.ssRestBlocked,message:e.message}))""")
            assert blocked["blocked"],str(blocked)
            time.sleep(8)
            during=state()
            assert during["bars"]==offline["bars"] and during["last"]==offline["last"] and during["drawings"]==before["drawings"],"Cached chart or drawings changed offline"
            assert during["rest"]["success"]==offline["rest"]["success"],"Unexpected successful market requests while offline"
            c.screenshot(out/"stability-offline.png")
        finally:condition()
        recovered=wait_for(lambda:(s if fresh(s:=state()) and s["rest"]["lastSuccess"]>during["rest"]["lastSuccess"] else None),90,"Fresh market data after reconnect")
        assert recovered["drawings"]==before["drawings"] and recovered["last"]>=before["last"] and recovered["gaps"]<=before["gaps"],"Data loss or gaps after reconnect"
        if before["status"]=="live":
            recovered=wait_for(lambda:(s if fresh(s:=state()) and s["status"]=="live" and s["ws"]["received"]>before["ws"]["received"] else None),90,"WebSocket fresh quotes after reconnect")
        c.screenshot(out/"stability-reconnected.png")
        return {"offline_status":during["status"],"rest_blocked":during["rest"]["blocked"],"recovered_status":recovered["status"],"received_before":before["ws"]["received"],"received_after":recovered["ws"]["received"],"gaps":recovered["gaps"],"retained_drawings":len(recovered["drawings"])}
    run("Controlled offline and recovery",offline_recovery)
    def slow_network():
        before=state()
        try:
            # Chrome network emulation: 600 ms latency and 128/64 kbit/s, not a physical radio test.
            condition(False,600,16000,8000)
            start=time.monotonic()
            result=c.js("""readMarket('kline',{...market,limit:'3'}).then(a=>({ok:Array.isArray(a)&&a.length>0,count:a.length}),e=>({ok:false,message:e.message}))""")
            elapsed=round(time.monotonic()-start,2)
            s=state()
            assert result["ok"],str(result)
            assert s["ordered"] and s["bars"]>=20 and s["drawings"]==before["drawings"]
            c.screenshot(out/"stability-slow.png")
            return {"latency_ms":600,"down_kbit_s":128,"up_kbit_s":64,"request_seconds":elapsed,"rows":result["count"]}
        finally:condition()
    run("Throttled slow network",slow_network)
    def soak():
        c.call("Performance.enable")
        samples=[]
        before=state()
        for i in range(7):
            if i:time.sleep(20)
            s=state()
            metrics={a["name"]:a["value"] for a in c.call("Performance.getMetrics")["metrics"]}
            samples.append({"elapsed":i*20,"heap":metrics.get("JSHeapUsedSize"),"nodes":metrics.get("Nodes"),"documents":metrics.get("Documents"),"bars":s["bars"],"status":s["status"],"rest_success":s["rest"]["success"],"ws_received":s["ws"]["received"]})
            assert s["ready"] and s["ordered"] and s["bars"]>=20 and s["drawings"]==before["drawings"],"Chart lost state during soak"
        report["soak_samples"]=samples
        assert samples[-1]["rest_success"]>samples[0]["rest_success"],"No fresh market requests during soak"
        heap=[a["heap"] for a in samples if a["heap"] is not None]
        assert heap,"JS heap metric unavailable"
        # Broad guard against runaway growth, not proof of absence of leaks.
        assert max(heap)<max(heap[0]*3,heap[0]+64*1024*1024),"Large heap growth"
        c.screenshot(out/"stability-soak.png")
        return {"duration_seconds":120,"heap_start_mib":round(heap[0]/1048576,2),"heap_end_mib":round(heap[-1]/1048576,2),"heap_peak_mib":round(max(heap)/1048576,2),"samples":len(samples),"fresh_requests":samples[-1]["rest_success"]-samples[0]["rest_success"]}
    run("Two minute foreground soak",soak)
    def isolation():
        r=c.js("""Promise.all(['workspace=1','transfer=paper','transfer=replay','push=1&device=qa-isolated-device-2026'].map(async q=>({route:q.split('&')[0],status:(await fetch('/safesize/?'+q,{cache:'no-store',credentials:'same-origin'})).status})))""")
        assert all(x["status"]==401 for x in r),str(r)
        return r
    run("Anonymous account transfer and push isolation",isolation)
    def notification():
        # Grant permission ONLY in disposable emulator, never subscribe or send real alerts.
        c.call("Browser.grantPermissions",{"origin":"https://alifatemii.ir","permissions":["notifications"]})
        r=c.js("""(async()=>{const timeout=(p,ms)=>Promise.race([p,new Promise((_,rej)=>setTimeout(()=>rej(Error('timeout')),ms))]);try{if(!('serviceWorker'in navigator))return {ok:false,error:'unsupported'};const reg=await timeout(navigator.serviceWorker.register('/safesize/?sw=1',{scope:'/safesize/',updateViaCache:'none'}),15000);const ready=reg.active?reg:await timeout(navigator.serviceWorker.ready,15000);return {ok:!!ready.active,scope:ready.scope,permission:Notification.permission,script:ready.active?.scriptURL}}catch(e){return {ok:false,error:e.message}}})()""")
        assert r["ok"] and r["scope"]=="https://alifatemii.ir/safesize/" and r["permission"]=="granted",str(r)
        report["service_worker"]=r
        # Direct local notification smoke test, not VAPID push or actual server delivery.
        try:
            n=c.js("""(async()=>{const reg=await navigator.serviceWorker.getRegistration('/safesize/');await reg.showNotification('SafeSize QA',{body:'Local emulator test',tag:'ss-qa-local'});const a=await reg.getNotifications({tag:'ss-qa-local'});return a.map(x=>({title:x.title,tag:x.tag}))})()""")
            assert any(x["tag"]=="ss-qa-local" for x in n),str(n)
            adb("shell","input","keyevent","KEYCODE_HOME")
            time.sleep(3)
            adb("shell","cmd","statusbar","expand-notifications")
            time.sleep(2)
            m.screenshot(dev,"stability-android-notification")
            return {"registered":r,"local_notification":n,"server_delivery":"not tested","background":"notification shade captured after Home"}
        finally:
            try:adb("shell","cmd","statusbar","collapse")
            except Exception:pass
            try:adb("shell","input","keyevent","KEYCODE_APP_SWITCH")
            except Exception:pass
            try:c.js("(async()=>{const reg=await navigator.serviceWorker.getRegistration('/safesize/');for(const n of await reg.getNotifications({tag:'ss-qa-local'}))n.close();return true})()")
            except Exception:pass
    run("Service worker and local notification",notification)
    failed=[x for x in cases if x["status"]=="fail"]
    report["stability_summary"]={"passed":len(cases)-len(failed),"failed":len(failed)}
    if failed:raise RuntimeError("Stability checks failed: "+json.dumps(failed,ensure_ascii=False)[:2000])
