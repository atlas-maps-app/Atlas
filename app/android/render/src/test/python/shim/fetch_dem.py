# Oracle-only shim: render/terrain.py and render/relief.py still import `fetch_dem`, which moved to fetch/dem.py.
# This loads fetch/dem.py under the old name, so today's scripts run unchanged.
import importlib.util, os
_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..', '..', '..', '..', '..', 'fetch', 'dem.py')
_spec = importlib.util.spec_from_file_location('_fetch_dem_real', os.path.normpath(_path))
_m = importlib.util.module_from_spec(_spec); _spec.loader.exec_module(_m)
globals().update({k: v for k, v in vars(_m).items() if not k.startswith('__')})
