/* Original interface symbols, shared by the client and server workspace. */
(() => {
  const paths = {
    hash: '<path d="M5 9h14M4 15h14M11 3 7 21M17 3l-4 18"/>',
    volume: '<path d="m11 5-6 4H2v6h3l6 4zM15 8a6 6 0 0 1 0 8M18 4a11 11 0 0 1 0 16"/>',
    mic: '<rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3M8 22h8"/>',
    'mic-off': '<path d="m3 3 18 18M9 9v3a3 3 0 0 0 4 3M9 5V4a3 3 0 0 1 6 0v7M5 10v2a7 7 0 0 0 12 5M19 10v2M12 19v3M8 22h8"/>',
    headphones: '<path d="M3 14v-3a9 9 0 0 1 18 0v3"/><rect x="3" y="12" width="4" height="8" rx="2"/><rect x="17" y="12" width="4" height="8" rx="2"/>',
    'headphones-off': '<path d="m2 2 20 20M3 14v-3a9 9 0 0 1 1-4M8 3a9 9 0 0 1 13 8v3"/><rect x="3" y="12" width="4" height="8" rx="2"/><path d="M17 17v1a2 2 0 0 0 4 0v-4a2 2 0 0 0-2-2"/>',
    settings: '<path d="m9 3 1-1h4l1 1 .4 2 2 .9 2-.6 2 3.4-1.6 1.5V13l1.6 1.5-2 3.5-2-.6-2 .9L15 21h-6l-.4-2-2-.9-2 .6-2-3.4L4.2 13v-2.8L2.6 8.7l2-3.4 2 .6 2-.9Z"/><circle cx="12" cy="11.5" r="3"/>',
    users: '<circle cx="9" cy="8" r="3"/><path d="M2 21v-3a7 7 0 0 1 14 0v3M16 4a3 3 0 0 1 0 6M19 14a5 5 0 0 1 3 4v3"/>',
    friends: '<circle cx="9" cy="8" r="3"/><path d="M2 21v-3a7 7 0 0 1 14 0v3M16 4a3 3 0 0 1 0 6M19 14a5 5 0 0 1 3 4v3"/>',
    message: '<path d="M21 14a3 3 0 0 1-3 3H8l-6 4V5a3 3 0 0 1 3-3h13a3 3 0 0 1 3 3Z"/><path d="M6 7h10M6 11h7"/>',
    search: '<circle cx="10" cy="10" r="7"/><path d="m15 15 6 6"/>',
    plus: '<path d="M12 4v16M4 12h16"/>',
    close: '<path d="m6 6 12 12M18 6 6 18"/>',
    chevron: '<path d="m6 9 6 6 6-6"/>',
    chevronRight: '<path d="m9 6 6 6-6 6"/>',
    menu: '<path d="M3 5h18M3 12h18M3 19h18"/>',
    invite: '<circle cx="9" cy="7" r="4"/><path d="M1 21v-3a8 8 0 0 1 13-6M19 13v8M15 17h8"/>',
    pin: '<path d="m8 3 13 13-4 1-3 4-3-7-7-3 4-3ZM3 21l6-6"/>',
    phone: '<path d="M7 3H3c-1 0-1 2-1 3 0 8 8 16 16 16 1 0 3 0 3-1v-4l-5-2-2 3a15 15 0 0 1-8-8l3-2Z"/>',
    disconnect: '<path d="M3 17 1 12c6-6 16-6 22 0l-2 5-5-1v-4a15 15 0 0 0-8 0v4Z"/>',
    bell: '<path d="M5 16V9a7 7 0 1 1 14 0v7l2 2H3ZM9 21h6"/>',
    emoji: '<circle cx="12" cy="12" r="10"/><path d="M8 9h.01M16 9h.01M7 14c2 5 8 5 10 0"/>',
    reply: '<path d="m9 4-6 6 6 6M3 10h10c6 0 8 4 8 10"/>',
    edit: '<path d="m16 3 5 5-13 13H3v-5ZM13 6l5 5"/>',
    trash: '<path d="M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7M14 10v7"/>',
    more: '<circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/>',
    download: '<path d="M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5"/>',
    copy: '<rect x="8" y="8" width="13" height="13" rx="2"/><path d="M16 8V3H3v13h5"/>',
    logout: '<path d="M9 3H3v18h6M8 12h13m-5-5 5 5-5 5"/>',
    shield: '<path d="m12 2 9 4v7c0 4-9 9-9 9s-9-5-9-9V6ZM8 12l3 3 5-6"/>'
  };
  window.PulseIcons = (name) => `<svg viewBox="0 0 24 24" class="ui-icon" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name] || paths.hash}</svg>`;
})();
