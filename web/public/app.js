'use strict';

// ============================ 工具 ============================

const $ = selector => document.querySelector(selector);

const SVG_NS = 'http://www.w3.org/2000/svg';

function icon(name) {
  const svg = document.createElementNS(SVG_NS, 'svg');
  const use = document.createElementNS(SVG_NS, 'use');
  use.setAttribute('href', '#' + name);
  svg.appendChild(use);
  return svg;
}

function h(tag, props, ...kids) {
  const el = document.createElement(tag);
  if (props) {
    for (const [key, value] of Object.entries(props)) {
      if (value === null || value === undefined || value === false) continue;
      if (key === 'class') el.className = value;
      else if (key === 'text') el.textContent = value;
      else if (key === 'onclick') el.addEventListener('click', value);
      else if (key === 'oninput') el.addEventListener('input', value);
      else if (key === 'onchange') el.addEventListener('change', value);
      else if (key === 'onkeydown') el.addEventListener('keydown', value);
      else el.setAttribute(key, value === true ? '' : String(value));
    }
  }
  for (const kid of kids.flat(Infinity)) {
    if (kid === null || kid === undefined || kid === false) continue;
    el.appendChild(typeof kid === 'object' && kid instanceof Node
      ? kid
      : document.createTextNode(String(kid)));
  }
  return el;
}

function clickable(el, handler) {
  el.setAttribute('role', 'button');
  el.setAttribute('tabindex', '0');
  el.addEventListener('click', handler);
  el.addEventListener('keydown', event => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      handler();
    }
  });
  return el;
}

function toMinutes(value) {
  const [hours, minutes] = String(value || '').split(':').map(Number);
  if (!Number.isFinite(hours) || !Number.isFinite(minutes)) return NaN;
  return hours * 60 + minutes;
}

function formatDate(iso) {
  const [year, month, day] = String(iso || '').split('-').map(Number);
  if (!year || !month || !day) return { md: iso || '', weekday: '', full: iso || '' };
  const date = new Date(year, month - 1, day);
  const weekday = '周' + '日一二三四五六'[date.getDay()];
  return { md: `${month}月${day}日`, weekday, full: `${year}年${month}月${day}日` };
}

function todayIso() {
  const now = new Date();
  return [now.getFullYear(), String(now.getMonth() + 1).padStart(2, '0'), String(now.getDate()).padStart(2, '0')].join('-');
}

function sanitizeHour(raw, max) {
  const digits = String(raw == null ? '' : raw).replace(/\D/g, '').slice(0, 2);
  if (!digits) return '';
  const value = Number(digits);
  return value > max ? String(max).padStart(2, '0') : digits;
}

function sanitizeMinute(raw) {
  return sanitizeHour(raw, 59);
}

function timeValid(hour, minute, allow2400) {
  if (!hour || !minute) return false;
  const hours = Number(hour);
  const minutes = Number(minute);
  if (!Number.isFinite(hours) || !Number.isFinite(minutes)) return false;
  if (minutes > 59) return false;
  if (hours === 24) return Boolean(allow2400) && minutes === 0;
  return hours >= 0 && hours <= 23;
}

function composeTime(hour, minute) {
  return `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
}

// ============================ 状态 ============================

const state = {
  username: '',
  days: [],
  selectedDayId: null,
  sortMode: 'default',
  tab: 'overview',
  searching: false,
  query: '',
  editing: null,
  busy: false,
};

const SORT_CYCLE = { default: 'completion', completion: 'time', time: 'default' };
const SORT_ICON = { default: 'i-sort-default', completion: 'i-sort-done', time: 'i-sort-time' };
const SORT_LABEL = { default: '默认', completion: '是否完成', time: '时间' };

// ============================ 网络 ============================

async function api(route, options = {}) {
  const method = options.method || 'GET';
  const hasBody = options.body !== undefined;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 15000);
  let response;
  try {
    response = await fetch('/api/' + route, {
      method,
      headers: hasBody ? { 'Content-Type': 'application/json' } : undefined,
      body: hasBody ? JSON.stringify(options.body) : undefined,
      signal: controller.signal,
      credentials: 'same-origin',
    });
  } catch (error) {
    throw new Error(error && error.name === 'AbortError' ? '请求超时，请检查网络' : '无法连接服务器');
  } finally {
    clearTimeout(timer);
  }
  let payload = null;
  try {
    payload = await response.json();
  } catch (_error) {
    payload = { ok: false };
  }
  if (response.status === 401 && route !== 'login') {
    showLogin('登录已过期，请重新登录');
    const error = new Error('unauthorized');
    error.expired = true;
    throw error;
  }
  if (!response.ok || !payload.ok) throw new Error(payload.error || `请求失败 (${response.status})`);
  return payload;
}

async function loadDays() {
  const payload = await api('days');
  state.days = Array.isArray(payload.days) ? payload.days : [];
  if (state.selectedDayId && !state.days.some(day => day.id === state.selectedDayId)) {
    state.selectedDayId = null;
  }
}

// ============================ 提示 ============================

function toast(message) {
  const host = $('#toasts');
  const node = h('div', { class: 'toast', text: message });
  host.appendChild(node);
  setTimeout(() => node.remove(), 2800);
}

function healthCheck() {
  fetch('/api/health', { credentials: 'same-origin' })
    .then(response => response.json())
    .then(() => {
      const el = $('#health');
      el.textContent = '后端服务：已连接 ✓';
      el.style.color = '#2f9e44';
    })
    .catch(() => {
      const el = $('#health');
      el.textContent = '后端服务：连接失败 ✗ 请确认已启动 node server.js';
      el.style.color = 'var(--favorite)';
    });
}

// ============================ 视图切换 ============================

function showLogin(message) {
  $('#login-view').classList.remove('hidden');
  $('#app').classList.add('hidden');
  $('#login-error').textContent = message || '';
  $('#login-password').value = '';
}

function enterApp() {
  $('#login-view').classList.add('hidden');
  $('#app').classList.remove('hidden');
  showHome();
}

function showHome() {
  state.selectedDayId = null;
  $('#view-home').classList.remove('hidden');
  $('#view-detail').classList.add('hidden');
  renderHome();
}

function showDetail(dayId) {
  state.selectedDayId = dayId === undefined ? state.selectedDayId : dayId;
  $('#view-home').classList.add('hidden');
  $('#view-detail').classList.remove('hidden');
  renderDetail();
}

// ============================ 主页 ============================

function dayComplete(day) {
  return day.activities.length > 0 && day.activities.every(activity => activity.state.completed);
}

function nowMinutes() {
  const now = new Date();
  return now.getHours() * 60 + now.getMinutes();
}

// 卡片下方「正在进行的事件」，按优先级阶梯取且仅取一条
function currentActivity(day, minutes) {
  const activities = day.activities;
  if (!activities.length) return null;
  const start = activity => toMinutes(activity.startTime);
  const end = activity => toMinutes(activity.endTime);
  const pending = activities.filter(activity => !activity.state.completed);

  const ongoing = pending.filter(activity => start(activity) <= minutes && minutes <= end(activity));
  if (ongoing.length) return ongoing.reduce((best, item) => (start(item) >= start(best) ? item : best));

  const upcoming = pending.filter(activity => start(activity) > minutes);
  if (upcoming.length) return upcoming.reduce((best, item) => (start(item) <= start(best) ? item : best));

  if (pending.length) return pending.reduce((best, item) => (start(item) <= start(best) ? item : best));

  return activities.reduce((best, item) => (end(item) >= end(best) ? item : best));
}

function isOngoing(activity, minutes) {
  if (!activity || activity.state.completed) return false;
  return toMinutes(activity.startTime) <= minutes && minutes <= toMinutes(activity.endTime);
}

function renderHome() {
  const list = $('#day-list');
  list.replaceChildren();
  const minutes = nowMinutes();

  if (!state.days.length) {
    list.appendChild(h('div', { class: 'empty', text: '还没有任何日期，点下面的「新增日期」开始安排' }));
    return;
  }

  const days = state.days.slice().sort((left, right) => {
    const leftDone = dayComplete(left);
    const rightDone = dayComplete(right);
    if (leftDone !== rightDone) return leftDone ? 1 : -1;
    return String(left.date).localeCompare(String(right.date));
  });

  for (const day of days) {
    const activity = currentActivity(day, minutes);
    const ongoing = isOngoing(activity, minutes);
    const date = formatDate(day.date);
    const detailText = activity ? (activity.state.note || activity.detail) : '';

    const card = clickable(
      h('div', { class: 'card day-card press' + (ongoing ? ' day-card-active' : '') },
        h('div', { class: 'day-top' },
          h('div', { class: 'day-location', text: day.location }),
          h('div', { class: 'day-date' },
            h('span', { text: date.md }),
            h('span', { class: 'weekday', text: ' ' + date.weekday }))),
        h('div', { class: 'day-event' + (ongoing ? ' is-now' : '') },
          activity
            ? h('div', { class: 'event-loc grow1', text: activity.location })
            : h('div', { class: 'event-empty grow1', text: '暂无日程' }),
          activity
            ? h('div', { class: 'event-right' },
                h('div', { class: 'event-time' },
                  h('span', { text: `${activity.startTime} 至 ${activity.endTime}` }),
                  ongoing ? h('span', { class: 'badge-now', text: '进行中' }) : null),
                detailText ? h('div', { class: 'event-detail', text: detailText }) : null)
            : null),
        day.note ? h('div', { class: 'day-note', text: day.note }) : null),
      () => showDetail(day.id));
    list.appendChild(card);
  }
}

// ============================ 详情页 ============================

function detailRows() {
  const rows = [];
  for (const day of state.days) {
    if (state.selectedDayId && day.id !== state.selectedDayId) continue;
    for (const activity of day.activities) {
      rows.push(Object.assign({}, activity, { dayId: day.id, dayDate: day.date, dayLocation: day.location }));
    }
  }
  return rows;
}

function sortRows(rows) {
  const byTime = (left, right) =>
    (toMinutes(left.startTime) - toMinutes(right.startTime)) ||
    String(left.location).localeCompare(String(right.location), 'zh');
  const list = rows.slice();
  if (state.sortMode === 'completion') {
    list.sort((left, right) =>
      (left.state.completed ? 1 : 0) - (right.state.completed ? 1 : 0) || byTime(left, right));
  } else if (state.sortMode === 'time') {
    list.sort(byTime);
  } else {
    list.sort((left, right) =>
      String(left.dayDate).localeCompare(String(right.dayDate)) || byTime(left, right));
  }
  return list;
}

function visibleRows() {
  let rows = detailRows();
  if (state.tab === 'plan') rows = rows.filter(row => row.state.favorite);
  const query = state.query.trim().toLowerCase();
  if (query) {
    rows = rows.filter(row => [row.location, row.startTime, row.endTime, row.detail, row.state.note, row.dayLocation, row.dayDate]
      .some(value => String(value || '').toLowerCase().includes(query)));
  }
  return sortRows(rows);
}

function renderDetail() {
  const day = state.days.find(item => item.id === state.selectedDayId) || null;
  $('#detail-day-pill').replaceChildren(
    document.createTextNode(day ? `${formatDate(day.date).md} ${day.location}` : '全部日期'),
    icon('i-chevron-right'));
  $('#detail-sort').replaceChildren(icon(SORT_ICON[state.sortMode]));
  $('#detail-searchbar').classList.toggle('hidden', !state.searching);
  $('#detail-query').value = state.query;
  $('#tab-overview').classList.toggle('selected', state.tab === 'overview');
  $('#tab-plan').classList.toggle('selected', state.tab === 'plan');

  const list = $('#activity-list');
  list.replaceChildren();
  const rows = visibleRows();

  if (!rows.length) {
    const message = state.tab === 'plan'
      ? '还没有收藏的日程'
      : (state.query.trim() ? '没有匹配的日程' : '这一天还没有日程，点右上角「＋」新建');
    list.appendChild(h('div', { class: 'empty', text: message }));
    return;
  }
  for (const row of rows) list.appendChild(activityCard(row));
}

function roundButton(iconName, extraClass, label, handler) {
  return h('button', {
    class: 'round-btn press' + (extraClass ? ' ' + extraClass : ''),
    type: 'button',
    'aria-label': label,
    onclick: handler,
  }, icon(iconName));
}

function activityCard(activity) {
  const editing = state.editing && state.editing.id === activity.id;
  const draft = editing ? state.editing : null;

  const top = h('div', { class: 'activity-top' });
  if (editing) {
    top.appendChild(h('input', {
      class: 'loc-input',
      value: draft.location,
      maxlength: 100,
      placeholder: '地点',
      oninput: event => { draft.location = event.target.value; },
    }));
  } else {
    top.appendChild(h('div', { class: 'activity-loc', text: activity.location }));
  }

  const doneButton = roundButton('i-check', activity.state.completed ? 'done' : '', activity.state.completed ? '标记未完成' : '标记完成', () => {
    toggleState(activity, { completed: !activity.state.completed });
  });
  const favButton = roundButton(
    activity.state.favorite ? 'i-heart-filled' : 'i-heart',
    activity.state.favorite ? 'fav' : '',
    activity.state.favorite ? '取消收藏' : '收藏',
    () => {
      const next = !activity.state.favorite;
      if (next) {
        favButton.classList.add('pop');
        setTimeout(() => favButton.classList.remove('pop'), 460);
      }
      toggleState(activity, { favorite: next });
    });
  const editButton = roundButton(
    editing ? 'i-save' : 'i-edit',
    '',
    editing ? '保存' : '编辑',
    () => (editing ? saveEdit(activity) : startEdit(activity)));

  top.appendChild(h('div', { class: 'activity-actions' }, doneButton, favButton, editButton));

  const timeCol = h('div', { class: 'time-col' });
  if (editing) {
    timeCol.appendChild(h('div', { class: 'time-field' },
      digitInput(draft.startHour, value => (draft.startHour = sanitizeHour(value, 23))),
      h('span', { class: 'mono muted', text: ':' }),
      digitInput(draft.startMinute, value => (draft.startMinute = sanitizeMinute(value)))));
    timeCol.appendChild(h('div', { class: 'sep', text: '至' }));
    timeCol.appendChild(h('div', { class: 'time-field' },
      digitInput(draft.endHour, value => (draft.endHour = sanitizeHour(value, 24))),
      h('span', { class: 'mono muted', text: ':' }),
      digitInput(draft.endMinute, value => (draft.endMinute = sanitizeMinute(value)))));
    if (draft.error) timeCol.appendChild(h('div', { class: 'field-error', text: '00:00-24:00' }));
  } else {
    timeCol.appendChild(h('div', { class: 'time-text', text: activity.startTime }));
    timeCol.appendChild(h('div', { class: 'sep', text: '至' }));
    timeCol.appendChild(h('div', { class: 'time-text', text: activity.endTime }));
  }

  const noteNode = editing
    ? h('textarea', {
        class: 'note-input',
        maxlength: 500,
        placeholder: '备注',
        oninput: event => { draft.note = event.target.value; },
      }, draft.note)
    : clickable(
        h('div', { class: 'note-box' + (activity.state.note ? ' editable' : ''),
          text: activity.state.note || '点击添加备注' }),
        () => startEdit(activity));

  const meta = [];
  if (!state.selectedDayId) {
    meta.push(h('span', { class: 'tiny muted', text: `${formatDate(activity.dayDate).md} ${activity.dayLocation}` }));
  }
  if (activity.detail) meta.push(h('span', { class: 'tiny muted', text: activity.detail }));

  const children = [];
  if (meta.length) children.push(h('div', { class: 'row wrap' }, meta));
  children.push(h('div', { class: 'activity-bottom' }, timeCol, noteNode));
  return h('div', { class: 'card activity-card' }, top, children);
}

function digitInput(value, onValue) {
  return h('input', {
    class: 'time-digit',
    inputmode: 'numeric',
    maxlength: 2,
    placeholder: '00',
    value,
    oninput: event => {
      const next = onValue(event.target.value);
      if (next !== undefined && event.target.value !== next) event.target.value = next;
    },
  });
}

// ============================ 每人状态 ============================

async function toggleState(activity, patch) {
  Object.assign(activity.state, patch);
  renderDetail();
  try {
    const payload = await api(`activities/${encodeURIComponent(activity.id)}/state`, { method: 'PUT', body: patch });
    Object.assign(activity.state, payload.state);
    renderDetail();
  } catch (error) {
    if (error.expired) return;
    Object.assign(activity.state, Object.fromEntries(Object.keys(patch).map(key => [key, !patch[key]])));
    renderDetail();
    toast(error.message);
  }
}

function startEdit(activity) {
  state.editing = {
    id: activity.id,
    location: activity.location,
    note: activity.state.note,
    startHour: activity.startTime.slice(0, 2),
    startMinute: activity.startTime.slice(3, 5),
    endHour: activity.endTime.slice(0, 2),
    endMinute: activity.endTime.slice(3, 5),
    error: false,
  };
  renderDetail();
}

async function saveEdit(activity) {
  const draft = state.editing;
  if (!draft) return;
  const startOk = timeValid(draft.startHour, draft.startMinute, false);
  const endOk = timeValid(draft.endHour, draft.endMinute, true);
  const startTime = composeTime(draft.startHour, draft.startMinute);
  const endTime = composeTime(draft.endHour, draft.endMinute);
  const inverted = startOk && endOk && toMinutes(startTime) > toMinutes(endTime);
  if (!startOk || !endOk || inverted || !draft.location.trim()) {
    draft.error = true;
    renderDetail();
    return;
  }
  try {
    await api(`activities/${encodeURIComponent(activity.id)}`, {
      method: 'PATCH',
      body: { location: draft.location.trim(), startTime, endTime, detail: activity.detail },
    });
    await api(`activities/${encodeURIComponent(activity.id)}/state`, { method: 'PUT', body: { note: draft.note } });
    state.editing = null;
    await loadDays();
    renderDetail();
    toast('已保存');
  } catch (error) {
    if (!error.expired) toast(error.message);
  }
}

// ============================ 弹层 ============================

function closeOverlay() {
  const scrim = $('#overlays').firstElementChild;
  if (scrim) scrim.remove();
}

function openOverlay(node, bottom) {
  const scrim = h('div', { class: 'scrim ' + (bottom ? 'bottom' : 'center') });
  scrim.appendChild(node);
  scrim.addEventListener('click', event => {
    if (event.target === scrim) closeOverlay();
  });
  $('#overlays').replaceChildren(scrim);
  return scrim;
}

function confirmDialog({ title, body, confirmLabel, danger, onConfirm }) {
  const node = h('div', { class: 'dialog' },
    h('div', { class: 'dialog-title', text: title }),
    h('div', { class: 'dialog-body', text: body }),
    h('div', { class: 'row-end' },
      h('button', { class: 'text-btn', type: 'button', text: '取消', onclick: closeOverlay }),
      h('button', {
        class: 'text-btn strong' + (danger ? ' danger' : ''),
        type: 'button',
        text: confirmLabel,
        onclick: () => { closeOverlay(); onConfirm(); },
      })));
  openOverlay(node, false);
}

// ---------- 日期表单 ----------

function openDayForm(existing) {
  const dateInput = h('input', { class: 'input', type: 'date', value: existing ? existing.date : todayIso() });
  const locationInput = h('input', { class: 'input', maxlength: 100, value: existing ? existing.location : '', placeholder: '例如：东京' });
  const noteInput = h('textarea', { class: 'input', maxlength: 500, placeholder: '可选' }, existing ? existing.note : '');
  const errorLine = h('div', { class: 'login-error' });

  const sheet = h('div', { class: 'sheet' },
    h('div', { class: 'sheet-title', text: existing ? '编辑日期' : '新增日期' }),
    h('label', { class: 'field' }, h('span', { text: '日期' }), dateInput),
    h('label', { class: 'field' }, h('span', { text: '地点' }), locationInput),
    h('label', { class: 'field' }, h('span', { text: '备注' }), noteInput),
    errorLine,
    h('div', { class: 'row-between' },
      existing
        ? h('button', {
            class: 'text-btn danger',
            type: 'button',
            text: '删除',
            onclick: () => {
              closeOverlay();
              confirmDialog({
                title: '删除日期',
                body: `确定删除「${formatDate(existing.date).md} ${existing.location}」吗？其下 ${existing.activities.length} 条日程会一并删除，且不可恢复。`,
                confirmLabel: '删除',
                danger: true,
                onConfirm: async () => {
                  try {
                    await api(`days/${encodeURIComponent(existing.id)}`, { method: 'DELETE' });
                    state.selectedDayId = null;
                    await loadDays();
                    renderDetail();
                    toast('已删除');
                  } catch (error2) {
                    if (!error2.expired) toast(error2.message);
                  }
                },
              });
            },
          })
        : h('span'),
      h('div', { class: 'row-end' },
        h('button', { class: 'text-btn', type: 'button', text: '取消', onclick: closeOverlay }),
        h('button', {
          class: 'text-btn strong',
          type: 'button',
          text: '保存',
          onclick: async () => {
            const nextDate = dateInput.value;
            const nextLocation = locationInput.value.trim();
            if (!/^\d{4}-\d{2}-\d{2}$/.test(nextDate)) { errorLine.textContent = '请选择日期'; return; }
            if (!nextLocation) { errorLine.textContent = '地点不能为空'; return; }
            try {
              if (existing) {
                await api(`days/${encodeURIComponent(existing.id)}`, {
                  method: 'PATCH',
                  body: { date: nextDate, location: nextLocation, note: noteInput.value },
                });
              } else {
                await api('days', { method: 'POST', body: { date: nextDate, location: nextLocation, note: noteInput.value } });
              }
              closeOverlay();
              await loadDays();
              if (existing && state.selectedDayId === existing.id) renderDetail();
              renderHome();
              toast(existing ? '已保存' : '已新增日期');
            } catch (error2) {
              if (!error2.expired) errorLine.textContent = error2.message;
            }
          },
        }))));

  openOverlay(sheet, true);
  setTimeout(() => locationInput.focus(), 60);
}

// ---------- 日期选择器（原「选择展台」改为「选择日期」） ----------

function openDayPicker() {
  const rows = [];
  rows.push(h('button', {
    class: 'choice' + (state.selectedDayId ? '' : ' selected'),
    type: 'button',
    onclick: () => { state.selectedDayId = null; state.editing = null; closeOverlay(); renderDetail(); },
  }, h('span', { text: '全部日期' }), h('span', { class: 'tiny muted', text: '查看所有日程' })));

  for (const day of state.days) {
    const date = formatDate(day.date);
    rows.push(h('div', { class: 'row' },
      h('button', {
        class: 'choice grow1' + (state.selectedDayId === day.id ? ' selected' : ''),
        type: 'button',
        onclick: () => { state.selectedDayId = day.id; state.editing = null; closeOverlay(); renderDetail(); },
      }, h('span', { text: `${date.md} ${day.location}` }), h('span', { class: 'tiny muted', text: date.weekday })),
      h('button', {
        class: 'icon-btn',
        type: 'button',
        'aria-label': '编辑日期',
        onclick: () => { closeOverlay(); openDayForm(day); },
      }, icon('i-edit'))));
  }

  const sheet = h('div', { class: 'sheet' },
    h('div', { class: 'sheet-title', text: '选择日期' }),
    rows.length > 1 ? rows : [h('div', { class: 'muted tiny', text: '还没有日期，先点「新增日期」' })],
    h('div', { class: 'row-end' },
      h('button', { class: 'text-btn strong', type: 'button', text: '完成', onclick: closeOverlay })));

  openOverlay(sheet, true);
}

// ---------- 活动表单 ----------

function openActivityForm() {
  const defaultDay = state.days.find(day => day.id === state.selectedDayId) || state.days[0] || null;
  let startHour = '';
  let startMinute = '';
  let endHour = '';
  let endMinute = '';
  const errorLine = h('div', { class: 'login-error' });

  if (!state.days.length) {
    toast('请先新增一个日期');
    openDayForm(null);
    return;
  }

  const daySelect = h('select', { class: 'input' },
    state.days.map(day => h('option', {
      value: day.id,
      selected: defaultDay && day.id === defaultDay.id,
    }, `${formatDate(day.date).md} ${day.location}`)));

  const locationInput = h('input', { class: 'input', maxlength: 100, placeholder: '例如：XX机场' });
  const detailInput = h('input', { class: 'input', maxlength: 200, placeholder: '可选，例如：T2航站楼 / XX航班' });

  const timeRow = h('div', { class: 'row' },
    h('div', { class: 'time-field' },
      digitInput('', value => { startHour = sanitizeHour(value, 23); return startHour; }),
      h('span', { class: 'mono muted', text: ':' }),
      digitInput('', value => { startMinute = sanitizeMinute(value); return startMinute; })),
    h('span', { class: 'muted', text: '至' }),
    h('div', { class: 'time-field' },
      digitInput('', value => { endHour = sanitizeHour(value, 24); return endHour; }),
      h('span', { class: 'mono muted', text: ':' }),
      digitInput('', value => { endMinute = sanitizeMinute(value); return endMinute; })));

  const sheet = h('div', { class: 'sheet' },
    h('div', { class: 'sheet-title', text: '新建日程' }),
    h('label', { class: 'field' }, h('span', { text: '日期' }), daySelect),
    h('label', { class: 'field' }, h('span', { text: '时间（必填）' }), timeRow),
    h('label', { class: 'field' }, h('span', { text: '地点（必填）' }), locationInput),
    h('label', { class: 'field' }, h('span', { text: '详情' }), detailInput),
    errorLine,
    h('div', { class: 'row-end' },
      h('button', { class: 'text-btn', type: 'button', text: '取消', onclick: closeOverlay }),
      h('button', {
        class: 'text-btn strong',
        type: 'button',
        text: '保存',
        onclick: async () => {
          const startOk = timeValid(startHour, startMinute, false);
          const endOk = timeValid(endHour, endMinute, true);
          const startTime = composeTime(startHour, startMinute);
          const endTime = composeTime(endHour, endMinute);
          if (!startOk || !endOk) { errorLine.textContent = '时间必填，格式 00:00-24:00'; return; }
          if (toMinutes(startTime) > toMinutes(endTime)) { errorLine.textContent = '开始时间不能晚于结束时间'; return; }
          if (!locationInput.value.trim()) { errorLine.textContent = '地点不能为空'; return; }
          try {
            await api(`days/${encodeURIComponent(daySelect.value)}/activities`, {
              method: 'POST',
              body: {
                location: locationInput.value.trim(),
                startTime,
                endTime,
                detail: detailInput.value,
              },
            });
            closeOverlay();
            state.selectedDayId = daySelect.value;
            await loadDays();
            renderDetail();
            toast('已新增日程');
          } catch (error) {
            if (!error.expired) errorLine.textContent = error.message;
          }
        },
      })));

  openOverlay(sheet, true);
  setTimeout(() => locationInput.focus(), 60);
}

// ============================ 事件绑定 ============================

$('#login-form').addEventListener('submit', async event => {
  event.preventDefault();
  const button = $('#login-submit');
  button.disabled = true;
  button.textContent = '登录中…';
  $('#login-error').textContent = '';
  try {
    const payload = await api('login', {
      method: 'POST',
      body: { username: $('#login-username').value.trim(), password: $('#login-password').value },
    });
    state.username = payload.username;
    await loadDays();
    enterApp();
  } catch (error) {
    $('#login-error').textContent = error.expired ? '登录已过期，请重新登录' : error.message;
  } finally {
    button.disabled = false;
    button.textContent = '登录';
  }
});

$('#home-add').addEventListener('click', () => openDayForm(null));

$('#home-logout').addEventListener('click', async () => {
  try {
    await api('logout', { method: 'POST', body: {} });
  } catch (_error) { /* 忽略，直接回登录页 */ }
  state.days = [];
  state.selectedDayId = null;
  showLogin('');
});

$('#detail-back').addEventListener('click', showHome);

$('#detail-day-pill').addEventListener('click', openDayPicker);

$('#detail-add').addEventListener('click', openActivityForm);

$('#detail-search').addEventListener('click', () => {
  state.searching = true;
  renderDetail();
  setTimeout(() => $('#detail-query').focus(), 40);
});

$('#detail-search-close').addEventListener('click', () => {
  state.searching = false;
  state.query = '';
  renderDetail();
});

$('#detail-query').addEventListener('input', event => {
  state.query = event.target.value;
  renderDetail();
});

$('#detail-sort').addEventListener('click', () => {
  state.sortMode = SORT_CYCLE[state.sortMode];
  renderDetail();
  toast(`已切换为${SORT_LABEL[state.sortMode]}排序`);
});

$('#tab-overview').addEventListener('click', () => { state.tab = 'overview'; renderDetail(); });
$('#tab-plan').addEventListener('click', () => { state.tab = 'plan'; renderDetail(); });

document.addEventListener('keydown', event => {
  if (event.key === 'Escape') {
    if ($('#overlays').firstElementChild) closeOverlay();
    else if (!$('#view-detail').classList.contains('hidden')) showHome();
  }
});

// ============================ 启动 ============================

(function boot() {
  healthCheck();
  api('me')
    .then(async payload => {
      state.username = payload.username;
      await loadDays();
      enterApp();
    })
    .catch(error => {
      if (!error.expired) showLogin('');
    });
})();
