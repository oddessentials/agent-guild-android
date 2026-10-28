export function fakeFetch(routes) {
  const calls = [];
  const fetch = async (url, init = {}) => {
    const u = new URL(url);
    const key = `${init.method ?? 'GET'} ${u.host}${u.pathname}`;
    calls.push({ key, url: u, init });
    const route = routes[key];
    if (!route) return new Response(`no route for ${key}`, { status: 599 });
    const handler = Array.isArray(route) ? route.shift() : route;
    const out = typeof handler === 'function' ? handler({ url: u, init }) : handler;
    const body = out.body === undefined ? '' : typeof out.body === 'string' ? out.body : JSON.stringify(out.body);
    return new Response(out.status === 204 ? null : body, { status: out.status ?? 200, headers: out.headers ?? {} });
  };
  return { fetch, calls, keys: () => calls.map((c) => c.key) };
}
