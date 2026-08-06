/*
 * Schema-driven rule specification editor (§21.3, §23.3).
 *
 * The form is generated from the JSON Schema published by the factory of the selected
 * rule type — the very same schema the engine validates against (§12), so the form and
 * the service cannot drift. Each property is rendered from its x-widget annotation. The
 * "facteur cent" trap is respected: a `rate` widget stores a fraction (0.05) but shows a
 * percentage (5), distinct from a `percent` widget that stores the percentage itself
 * (§21.3). A Form / JSON toggle always exposes the raw specification.
 */
(function () {
    'use strict';

    function parseJson(id) {
        var el = document.getElementById(id);
        if (!el || !el.textContent.trim()) { return null; }
        try { return JSON.parse(el.textContent); } catch (e) { return null; }
    }

    var schemas = parseJson('rule-schemas') || {};
    var communities = parseJson('rule-communities') || [];
    var initialSpec = parseJson('rule-spec-initial') || {};

    var typeSel = document.getElementById('rule-type');
    var host = document.getElementById('schema-fields');
    var specInput = document.getElementById('specification');
    var jsonArea = document.getElementById('json-editor');
    var formHost = document.getElementById('schema-form-host');
    var jsonHost = document.getElementById('json-host');
    if (!typeSel || !host) { return; }

    var collectors = [];

    function el(tag, cls, text) {
        var node = document.createElement(tag);
        if (cls) { node.className = cls; }
        if (text != null) { node.textContent = text; }
        return node;
    }

    function labelFor(prop, name) { return (prop && prop['x-label']) || name; }

    function resolveRef(prop, schema) {
        if (prop && prop.$ref) {
            var parts = prop.$ref.replace('#/', '').split('/');
            var node = schema;
            parts.forEach(function (p) { node = node[p]; });
            return node;
        }
        return prop;
    }

    function field(labelText, control) {
        var wrap = el('label', 'field');
        wrap.appendChild(el('span', 'field-label', labelText));
        wrap.appendChild(control);
        return wrap;
    }

    function buildNumber(name, prop, value, opts) {
        var input = el('input', 'input-control');
        input.type = 'number';
        input.step = opts.int ? '1' : '0.01';
        var shown = value;
        if (opts.factor && value != null) { shown = +(value * opts.factor).toFixed(6); }
        if (shown != null) { input.value = shown; }
        var control = input;
        if (opts.suffix) {
            var unit = el('div', 'input-unit');
            unit.appendChild(input);
            unit.appendChild(el('span', 'input-suffix', opts.suffix));
            control = unit;
        }
        collectors.push(function (out) {
            if (input.value === '') { return; }
            var num = parseFloat(input.value);
            if (isNaN(num)) { return; }
            out[name] = opts.factor ? +(num / opts.factor).toFixed(6) : (opts.int ? Math.round(num) : num);
        });
        return field(labelFor(prop, name), control);
    }

    function buildToggle(name, prop, value) {
        var input = el('input');
        input.type = 'checkbox';
        input.checked = !!value;
        var wrap = el('label', 'toggle');
        wrap.appendChild(input);
        wrap.appendChild(el('span', 'field-label', ' ' + labelFor(prop, name)));
        collectors.push(function (out) { out[name] = input.checked; });
        return wrap;
    }

    function buildList(name, prop, value) {
        var input = el('input', 'input-control');
        input.type = 'text';
        input.placeholder = 'valeurs séparées par des virgules';
        if (Array.isArray(value)) { input.value = value.join(', '); }
        collectors.push(function (out) {
            var parts = input.value.split(',').map(function (s) { return s.trim(); }).filter(Boolean);
            if (parts.length) { out[name] = parts; }
        });
        return field(labelFor(prop, name), input);
    }

    function buildCommunity(name, prop, value) {
        var sel = el('select', 'input-control');
        sel.appendChild(el('option', null, ''));
        communities.forEach(function (code) {
            var o = el('option', null, code);
            o.value = code;
            if (code === value) { o.selected = true; }
            sel.appendChild(o);
        });
        if (value && communities.indexOf(value) < 0) {
            var o = el('option', null, value); o.value = value; o.selected = true; sel.appendChild(o);
        }
        collectors.push(function (out) { if (sel.value) { out[name] = sel.value; } });
        return field(labelFor(prop, name), sel);
    }

    var DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];

    function buildDaySet(name, prop, value) {
        var wrap = el('div', 'field');
        wrap.appendChild(el('span', 'field-label', labelFor(prop, name)));
        var set = el('div', 'day-set');
        var boxes = [];
        DAYS.forEach(function (day) {
            var lbl = el('label');
            var box = el('input'); box.type = 'checkbox'; box.value = day;
            if (Array.isArray(value) && value.indexOf(day) >= 0) { box.checked = true; }
            boxes.push(box);
            lbl.appendChild(box); lbl.appendChild(document.createTextNode(' ' + day.slice(0, 3)));
            set.appendChild(lbl);
        });
        wrap.appendChild(set);
        collectors.push(function (out) {
            var days = boxes.filter(function (b) { return b.checked; }).map(function (b) { return b.value; });
            if (days.length) { out[name] = days; }
        });
        return wrap;
    }

    function buildObjectList(name, prop, value) {
        var wrap = el('div', 'field');
        var itemLabel = prop['x-item-label'] || 'élément';
        wrap.appendChild(el('span', 'field-label', labelFor(prop, name)));
        var rowsHost = el('div');
        var itemProps = (prop.items && prop.items.properties) || {};
        var rowStates = [];

        function addRow(rowValue) {
            var row = el('div', 'tier-row');
            var fieldGetters = {};
            Object.keys(itemProps).forEach(function (key) {
                var ip = itemProps[key];
                var w = ip['x-widget'];
                var v = rowValue ? rowValue[key] : undefined;
                var control, read;
                if (w === 'toggle') {
                    var cb = el('input'); cb.type = 'checkbox'; cb.checked = !!v;
                    var l = el('label', 'toggle'); l.appendChild(cb);
                    l.appendChild(el('span', 'field-label', ' ' + (ip['x-label'] || key)));
                    control = l; read = function () { return cb.checked; };
                } else {
                    var inp = el('input', 'input-control'); inp.type = 'number'; inp.step = '0.01';
                    if (v != null) { inp.value = v; }
                    var fl = field(ip['x-label'] || key, inp);
                    control = fl; read = function () { return inp.value === '' ? undefined : parseFloat(inp.value); };
                }
                row.appendChild(control);
                fieldGetters[key] = read;
            });
            var rm = el('button', 'btn btn-danger btn-small', '×'); rm.type = 'button';
            rm.onclick = function () { rowsHost.removeChild(row); rowStates.splice(rowStates.indexOf(state), 1); };
            row.appendChild(rm);
            var state = { getters: fieldGetters };
            rowStates.push(state);
            rowsHost.appendChild(row);
        }

        (Array.isArray(value) ? value : [null]).forEach(addRow);
        var add = el('button', 'btn btn-small', '+ ' + itemLabel); add.type = 'button';
        add.onclick = function () { addRow(null); };
        wrap.appendChild(rowsHost);
        wrap.appendChild(add);
        collectors.push(function (out) {
            var arr = rowStates.map(function (st) {
                var obj = {};
                Object.keys(st.getters).forEach(function (k) {
                    var val = st.getters[k]();
                    if (val !== undefined) { obj[k] = val; }
                });
                return obj;
            }).filter(function (o) { return Object.keys(o).length; });
            if (arr.length) { out[name] = arr; }
        });
        return wrap;
    }

    function buildScopeSet(container, title, prop, value) {
        container.appendChild(el('div', 'schema-scope-title', title));
        var grid = el('div', 'field-grid');
        ['brands', 'families', 'eans'].forEach(function (key) {
            var input = el('input', 'input-control'); input.type = 'text';
            input.placeholder = key + ' séparés par des virgules';
            if (value && Array.isArray(value[key])) { input.value = value[key].join(', '); }
            grid.appendChild(field(key, input));
            container._scopeReaders = container._scopeReaders || {};
            container._scopeReaders[key] = input;
        });
        container.appendChild(grid);
    }

    function buildScope(name, scopeSchema, value) {
        var wrap = el('div', 'field');
        wrap.appendChild(el('span', 'field-label', labelFor(scopeSchema, name)));
        var box = el('div', 'schema-scope');
        var whole = el('input'); whole.type = 'checkbox'; whole.checked = !!(value && value.wholeStore);
        var wl = el('label', 'toggle'); wl.appendChild(whole);
        wl.appendChild(el('span', 'field-label', ' Tout le magasin'));
        box.appendChild(wl);
        var inc = el('div'); buildScopeSet(inc, 'Inclusions', scopeSchema, value ? value.include : null); box.appendChild(inc);
        var exc = el('div'); buildScopeSet(exc, 'Exclusions', scopeSchema, value ? value.exclude : null); box.appendChild(exc);
        wrap.appendChild(box);
        collectors.push(function (out) {
            var scope = {};
            if (whole.checked) { scope.wholeStore = true; }
            var include = readScopeSet(inc); if (include) { scope.include = include; }
            var exclude = readScopeSet(exc); if (exclude) { scope.exclude = exclude; }
            out[name] = scope;
        });
        return wrap;
    }

    function readScopeSet(container) {
        var readers = container._scopeReaders || {};
        var set = {};
        Object.keys(readers).forEach(function (key) {
            var parts = readers[key].value.split(',').map(function (s) { return s.trim(); }).filter(Boolean);
            if (parts.length) { set[key] = parts; }
        });
        return Object.keys(set).length ? set : null;
    }

    function inferWidget(name) {
        if (/rate$/i.test(name)) { return 'rate'; }
        if (/(amount|cap|threshold|reward)$/i.test(name)) { return 'money'; }
        return 'text';
    }

    function render(type, spec) {
        host.innerHTML = '';
        collectors = [];
        var schema = schemas[type];
        if (!schema) { host.appendChild(el('p', 'placeholder-note', 'Type inconnu du registre.')); return; }
        var props = schema.properties || {};
        Object.keys(props).forEach(function (name) {
            var prop = props[name];
            var value = spec ? spec[name] : undefined;
            var widget = prop['x-widget'] || inferWidget(name);
            var node;
            if (name === 'scope' || widget === 'scope' || prop.$ref) {
                node = buildScope(name, resolveRef(prop, schema), value);
            } else if (widget === 'rate') {
                node = buildNumber(name, prop, value, { factor: 100, suffix: '%' });
            } else if (widget === 'percent') {
                node = buildNumber(name, prop, value, { suffix: '%' });
            } else if (widget === 'money') {
                node = buildNumber(name, prop, value, { suffix: '€' });
            } else if (widget === 'integer' || widget === 'day-of-month') {
                node = buildNumber(name, prop, value, { int: true });
            } else if (widget === 'toggle') {
                node = buildToggle(name, prop, value);
            } else if (widget === 'day-set') {
                node = buildDaySet(name, prop, value);
            } else if (widget === 'community') {
                node = buildCommunity(name, prop, value);
            } else if (widget === 'object-list') {
                node = buildObjectList(name, prop, value);
            } else if (widget === 'string-list' || widget === 'ean') {
                node = buildList(name, prop, value);
            } else {
                var input = el('input', 'input-control'); input.type = 'text';
                if (value != null) { input.value = value; }
                collectors.push(function (out) { if (input.value) { out[name] = input.value; } });
                node = field(labelFor(prop, name), input);
            }
            var wrapper = el('div', 'field-grid');
            wrapper.appendChild(node);
            host.appendChild(wrapper);
        });
    }

    function collect() {
        var out = {};
        collectors.forEach(function (fn) { fn(out); });
        return out;
    }

    function currentSpec() {
        // If the JSON editor is visible and has content, it wins (raw data escape hatch).
        if (jsonHost && !jsonHost.classList.contains('is-hidden') && jsonArea && jsonArea.value.trim()) {
            try { return JSON.parse(jsonArea.value); } catch (e) { return collect(); }
        }
        return collect();
    }

    typeSel.addEventListener('change', function () { render(typeSel.value, {}); });

    var toForm = document.getElementById('toggle-form');
    var toJson = document.getElementById('toggle-json');
    if (toJson) {
        toJson.addEventListener('click', function () {
            jsonArea.value = JSON.stringify(collect(), null, 2);
            formHost.classList.add('is-hidden');
            jsonHost.classList.remove('is-hidden');
        });
    }
    if (toForm) {
        toForm.addEventListener('click', function () {
            var spec = jsonArea.value.trim() ? (function () { try { return JSON.parse(jsonArea.value); } catch (e) { return collect(); } })() : collect();
            render(typeSel.value, spec);
            jsonHost.classList.add('is-hidden');
            formHost.classList.remove('is-hidden');
        });
    }

    var form = document.getElementById('rule-form');
    if (form) {
        form.addEventListener('submit', function () {
            specInput.value = JSON.stringify(currentSpec());
        });
    }

    render(typeSel.value, initialSpec);
})();
