import sys, glob, re, collections, json, os
import xml.etree.ElementTree as ET
files = sys.argv[1:]
el = collections.Counter(); attrs = collections.defaultdict(collections.Counter)
vals = collections.defaultdict(collections.Counter)
styleprops = collections.Counter(); parents=collections.defaultdict(collections.Counter)
fonts = collections.Counter(); errs=[]
interesting = {'fill-rule','clip-rule','stroke-linecap','stroke-linejoin','mix-blend-mode','paint-order','font-family','font-weight','font-style','text-anchor','dominant-baseline','gradientUnits','patternUnits','patternContentUnits','spreadMethod','type','operator','in','in2','result','filterUnits','primitiveUnits','clipPathUnits','maskUnits','maskContentUnits','stitchTiles','numOctaves','baseFrequency','seed','xChannelSelector','yChannelSelector','visibility','display','letter-spacing','font-size','vector-effect','shape-rendering','color-interpolation-filters','preserveAspectRatio','stroke-dasharray','filter','clip-path','mask','alignment-baseline','text-rendering','overflow','writing-mode','scale','stdDeviation','k1','k2','k3','k4','values','opacity','fill-opacity','stroke-opacity','stop-opacity','xml:space','viewBox','width','height','image-rendering','lengthAdjust','textLength','font-variant','style'}
def tag(t): return t.split('}')[-1]
for f in files:
    try:
        stack=[]
        for ev, e in ET.iterparse(f, events=('start','end')):
            t=tag(e.tag)
            if ev=='start':
                el[t]+=1
                if stack: parents[t][stack[-1]]+=1
                stack.append(t)
                for k,v in e.attrib.items():
                    k=tag(k) if '}' in k else k
                    attrs[t][k]+=1
                    if k in interesting: vals[k][v[:80]]+=1
                    if k=='style':
                        for p in v.split(';'):
                            if ':' in p: styleprops[p.split(':')[0].strip()]+=1
                    if k=='transform':
                        for m in re.findall(r'([a-zA-Z]+)\(',v): vals['transform-fn'][m]+=1
                    if k=='d':
                        for m in set(re.findall(r'[A-Za-z]',v)): vals['path-cmd'][m]+=1
                    if k in ('fill','stroke'):
                        kind = 'url' if v.startswith('url') else ('hex%d'%len(v) if v.startswith('#') else ('rgb' if v.startswith('rgb') else v[:20]))
                        vals[k+'-kind'][kind]+=1
            else:
                stack.pop()
                e.clear()
    except Exception as ex: errs.append((f,str(ex)))
out={'files':len(files),'errors':errs[:20],'elements':el.most_common(),'attrs':{k:v.most_common() for k,v in attrs.items()},'styleprops':styleprops.most_common(),'parents':{k:v.most_common(8) for k,v in parents.items()}}
out['vals']={k:v.most_common(40) for k,v in vals.items()}
json.dump(out, sys.stdout, indent=0)
print(file=sys.stderr); print(len(files), 'files', len(errs), 'errors', file=sys.stderr)
