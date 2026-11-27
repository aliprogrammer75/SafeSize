"""Advanced regression tests on the disposable Android browser."""
import json, time, math, traceback

def advanced_suite(c, report, out, check, wait_for, inspect, ready, tap_dom):
    cases=[]
    report["advanced"]=cases
    def run(name,fn):
        try:
            d=fn()
            cases.append({"name":name,"status":"pass","detail":d})
            check(name,str(d))
        except Exception as e:
            cases.append({"name":name,"status":"fail","detail":str(e)[:700]})
            print("FAIL:",name,str(e),flush=True)
            traceback.print_exc()
            try: c.screenshot(out/("advanced-fail-%02d.png"%len(cases)))
            except Exception: pass
        finally:
            try: c.js("if(placement||gesture)cancelTouch();document.querySelector('dialog[open]')?.close();true")
            except Exception: pass
    def rows():
        return c.js("drawings.map(d=>({id:d.id,name:d.name,points:d.points.map(p=>({timestamp:p.timestamp,value:p.value}))}))")
    baseline=rows()
    assert baseline,"Missing prior smoke-test drawing"
    ids=json.dumps([d["id"] for d in baseline])
    def clean():
        c.js("if(placement||gesture)cancelTouch();editDrawings(drawings.filter(d=>"+ids+".includes(d.id)),null);true")
    def plot():
        p=inspect(c)["plot"]
        assert p and p["w"]>100 and p["h"]>80
        return p
    def make(name):
        clean()
        before={d["id"] for d in rows()}
        p=plot()
        n=c.js("numberOfPoints["+json.dumps(name)+"]")
        pos=[(.23,.53),(.68,.29),(.40,.68),(.77,.44),(.31,.20),(.58,.73)]
        assert isinstance(n,int) and 1<=n<=len(pos)
        c.js("startPlacement("+json.dumps(name)+");true")
        for x,y in pos[:1 if name in ("longPosition","shortPosition") else n]:
            c.tap(p["x"]+x*p["w"],p["y"]+y*p["h"])
            time.sleep(.12)
        time.sleep(.25)
        a=[d for d in rows() if d["id"] not in before]
        assert len(a)==1,"Drawing not saved: "+name
        d=a[0]
        assert d["name"]==name and len(d["points"])==n,str(d)
        assert all(isinstance(v,(int,float)) and math.isfinite(v) for q in d["points"] for v in (q["timestamp"],q["value"]))
        return d
    report["advanced_scope"]="All declared point tools; text prompt excluded; brush separate; application API for indicators"
    for name in c.js("Object.keys(numberOfPoints)"):
        if name in ("ssText","ssBrush"): continue
        def draw(n=name):
            d=make(n)
            if n in ("fibonacciLine","rectangleZone","ssElliottImpulse","longPosition"): c.screenshot(out/("advanced-"+n+".png"))
            return {"points":len(d["points"])}
        run("Touch drawing "+name,draw)
    def brush():
        clean();p=plot();before={d["id"] for d in rows()}
        c.js("startPlacement('ssBrush');true")
        c.touch("touchStart",[(p["x"]+.2*p["w"],p["y"]+.55*p["h"])])
        for i in range(1,13):
            c.touch("touchMove",[(p["x"]+(.2+i*.045)*p["w"],p["y"]+(.55-.12*math.sin(i/3))*p["h"])])
            time.sleep(.05)
        c.touch("touchEnd",[]);time.sleep(.3)
        a=[d for d in rows() if d["id"] not in before]
        assert len(a)==1 and a[0]["name"]=="ssBrush" and len(a[0]["points"])>=3
        return {"points":len(a[0]["points"])}
    run("Touch brush",brush)
    def edit():
        d=make("segment")
        q=c.js("(id=>{const d=drawings.find(x=>x.id===id);selection(id);const r=chart.getDom('candle_pane','main').getBoundingClientRect(),p=atPane(r.x+r.width/2,r.y+r.height/2),g=geometry(d,p,mapper(p));return {x:p.rect.left+g.points[1].x,y:p.rect.top+g.points[1].y}})("+json.dumps(d["id"])+")")
        p=plot()
        assert p["x"]<q["x"]<p["x"]+p["w"] and p["y"]<q["y"]<p["y"]+p["h"]
        c.touch("touchStart",[(q["x"],q["y"])])
        for i in range(1,9):
            c.touch("touchMove",[(q["x"]+i*3,q["y"]+i*3)])
            time.sleep(.06)
        c.touch("touchEnd",[]);time.sleep(.3)
        a=next(x for x in rows() if x["id"]==d["id"])
        assert a["points"]!=d["points"],"Endpoint did not move"
        assert a["points"][0]==d["points"][0],"First endpoint changed"
        c.screenshot(out/"advanced-edit.png")
        return {"before":d["points"],"after":a["points"]}
    run("Touch endpoint edit",edit)
    def history():
        d=make("segment")
        c.js("document.getElementById('undo').click();true")
        assert not any(x["id"]==d["id"] for x in rows()),"Undo failed"
        c.js("document.getElementById('redo').click();true")
        assert d in rows(),"Redo failed"
        return "Exact drawing restored"
    run("Undo redo controls",history)
    def persist():
        d=make("fibonacciLine")
        c.call("Page.reload",{"ignoreCache":False})
        wait_for(lambda:ready(c,report),90,"Drawing reload")
        assert d in rows() and all(x in rows() for x in baseline),"Drawing not restored"
        c.screenshot(out/"advanced-restored.png")
        return "Saved Fib and original line restored"
    run("Drawing persistence",persist)
    for name,params in [("EMA",[20]),("MA",[9,21]),("BOLL",[20,2]),("RSI",[14]),("MACD",[12,26,9]),("ATR",[14])]:
        def indicator_case(n=name,p=params):
            k="QA_"+n
            try:
                c.js("indicator("+json.dumps(n)+","+json.dumps(p)+",{key:"+json.dumps(k)+"});saveInd();true")
                v=c.js("(k=>{const v=indicators[k];return v?{params:v.params,engine:!!chart.getIndicatorByPaneId(v.pane,v.engine)}:null})("+json.dumps(k)+")")
                assert v and v["engine"] and v["params"]==p,str(v)
                c.screenshot(out/("advanced-ind-"+n+".png"))
            finally:
                c.js("removeInd("+json.dumps(k)+");true")
            assert not c.js("!!indicators["+json.dumps(k)+"]")
            return "Added, parameters checked, removed"
        run("Indicator "+name,indicator_case)
    def replay_test():
        clean()
        stamp=c.js("chart.getDataList().at(-40).timestamp")
        c.js("openDate('replay');document.getElementById('chart-date').value=new Date("+str(stamp)+").toISOString().slice(0,16);document.getElementById('date-form').requestSubmit();true")
        def state():
            return c.js("replay?{index:replay.index,stamp:replay.bars[replay.index].timestamp,last:chart.getDataList().at(-1)?.timestamp}:null")
        try:
            first=wait_for(state,60,"Replay start")
            assert first["stamp"]==stamp==first["last"],str(first)
            for i in range(3):
                tap_dom(c,"#replay-step")
                a=wait_for(state,10,"Replay step")
                assert a["index"]==first["index"]+i+1 and a["last"]==a["stamp"] and a["stamp"]>first["stamp"],str(a)
            c.screenshot(out/"advanced-replay.png")
            tap_dom(c,"#replay-exit")
            wait_for(lambda:c.js("!replay&&chart.getDataList().length>=20"),30,"Replay exit")
            return {"start":first,"after":a}
        finally:
            try: c.js("if(replay)leaveReplay();true")
            except Exception: pass
    run("Replay three steps and exit",replay_test)
    failed=[x for x in cases if x["status"]=="fail"]
    report["advanced_summary"]={"passed":len(cases)-len(failed),"failed":len(failed)}
    if failed: raise RuntimeError("Advanced checks failed: "+json.dumps(failed,ensure_ascii=False)[:2400])
