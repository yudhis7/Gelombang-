'use strict';
// Small hand-drawn icon set (24×24 grid, stroke = currentColor).
(function () {
  const svg = (body, { fill = false } = {}) =>
    `<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" ${fill ? 'fill="currentColor" stroke="none"' : 'fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"'}>${body}</svg>`;

  const STAR = 'M12 3.6l2.55 5.2 5.7.83-4.13 4.02.98 5.68L12 16.64l-5.1 2.69.98-5.68L3.75 9.63l5.7-.83z';

  window.ICONS = {
    play: svg('<path d="M8 5.2v13.6a.8.8 0 0 0 1.2.7l10.6-6.8a.8.8 0 0 0 0-1.4L9.2 4.5A.8.8 0 0 0 8 5.2z"/>', { fill: true }),
    pause: svg('<rect x="6.5" y="5" width="4" height="14" rx="1.2"/><rect x="13.5" y="5" width="4" height="14" rx="1.2"/>', { fill: true }),
    stop: svg('<rect x="6" y="6" width="12" height="12" rx="2"/>', { fill: true }),
    next: svg('<path d="M6 6l8 6-8 6z" fill="currentColor"/><path d="M18 6v12"/>'),
    prev: svg('<path d="M18 6l-8 6 8 6z" fill="currentColor"/><path d="M6 6v12"/>'),
    star: svg(`<path d="${STAR}"/>`),
    starFill: svg(`<path d="${STAR}"/>`, { fill: true }),
    search: svg('<circle cx="11" cy="11" r="6.5"/><path d="M20 20l-4.2-4.2"/>'),
    plus: svg('<path d="M12 5v14M5 12h14"/>'),
    sliders: svg('<path d="M4 7h10M18 7h2M4 17h4M12 17h8"/><circle cx="16" cy="7" r="2"/><circle cx="10" cy="17" r="2"/>'),
    volume: svg('<path d="M4 9.5h3.2L12 5.5v13l-4.8-4H4z"/><path d="M15.5 9a4.2 4.2 0 0 1 0 6M18.2 6.5a8 8 0 0 1 0 11"/>'),
    volumeLow: svg('<path d="M4 9.5h3.2L12 5.5v13l-4.8-4H4z"/><path d="M15.5 9a4.2 4.2 0 0 1 0 6"/>'),
    mute: svg('<path d="M4 9.5h3.2L12 5.5v13l-4.8-4H4z"/><path d="M16 9.5l5 5M21 9.5l-5 5"/>'),
    moon: svg('<path d="M19.5 14.6A7.8 7.8 0 0 1 9.4 4.5a7.8 7.8 0 1 0 10.1 10.1z"/>'),
    external: svg('<path d="M14 4h6v6M20 4l-8.5 8.5M18 14v4.5A1.5 1.5 0 0 1 16.5 20h-11A1.5 1.5 0 0 1 4 18.5v-11A1.5 1.5 0 0 1 5.5 6H10"/>'),
    copy: svg('<rect x="8.5" y="8.5" width="11" height="11" rx="2"/><path d="M15.5 8.5V6a1.5 1.5 0 0 0-1.5-1.5H6A1.5 1.5 0 0 0 4.5 6v8A1.5 1.5 0 0 0 6 15.5h2.5"/>'),
    clock: svg('<circle cx="12" cy="12" r="8"/><path d="M12 7.5V12l3 2"/>'),
    home: svg('<path d="M4 11l8-6.5 8 6.5"/><path d="M6 9.5V19h12V9.5"/><path d="M10 19v-5h4v5"/>'),
    radio: svg('<rect x="3.5" y="8" width="17" height="11.5" rx="2.5"/><path d="M7 8l9-4"/><circle cx="15.5" cy="13.75" r="2.6"/><path d="M7 12h3M7 15.5h3"/>'),
    trend: svg('<path d="M4 16.5l5-5 3.5 3.5L20 7.5"/><path d="M14.5 7.5H20V13"/>'),
    chevron: svg('<path d="M9 6l6 6-6 6"/>'),
    more: svg('<circle cx="5.5" cy="12" r="1.4"/><circle cx="12" cy="12" r="1.4"/><circle cx="18.5" cy="12" r="1.4"/>', { fill: true }),
    close: svg('<path d="M6 6l12 12M18 6L6 18"/>'),
    trash: svg('<path d="M5 7h14M10 7V5h4v2M7 7l1 12h8l1-12"/>'),
    edit: svg('<path d="M15.5 5.5l3 3L9 18H6v-3z"/><path d="M13.5 7.5l3 3"/>'),
    globe: svg('<circle cx="12" cy="12" r="8"/><path d="M4 12h16M12 4c2.4 2.3 3.6 5 3.6 8s-1.2 5.7-3.6 8c-2.4-2.3-3.6-5-3.6-8S9.6 6.3 12 4z"/>'),
    wave: svg('<path d="M3 12h2M7 8v8M11 5v14M15 9v6M19 7v10M21 12h0"/>'),
    retry: svg('<path d="M4.5 12a7.5 7.5 0 0 1 13-5.1L20 9.5M20 4.5v5h-5M19.5 12a7.5 7.5 0 0 1-13 5.1L4 14.5M4 19.5v-5h5"/>'),
    upload: svg('<path d="M12 15V4M7.5 8.5L12 4l4.5 4.5M5 15v3.5A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5-1.5V15"/>'),
    download: svg('<path d="M12 4v11M7.5 10.5L12 15l4.5-4.5M5 15v3.5A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5-1.5V15"/>')
  };
})();
