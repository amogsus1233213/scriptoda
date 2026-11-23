// ==UserScript==
// @name         Chess Study Assistant
// @namespace    chess-study
// @version      2.0
// @description  Analysis helper for chess practice
// @match        *://*.chess.com/*
// @grant        none
// @run-at       document-idle
// ==/UserScript==

(function() {
  'use strict';

  var CS = window.ChessStudy = {};
  var _worker = null;
  var _ready = false;
  var _pending = null;
  var _busy = false;
  var _active = false;
  var _poll = null;
  var _gameOverPoll = null;
  var _dot = null;

  function findBoard() {
    return document.querySelector('wc-chess-board');
  }

  function fetchEngine() {
    return fetch('https://cdn.jsdelivr.net/npm/stockfish.js@10.0.2/stockfish.js')
      .then(function(r) { return r.text(); })
      .then(function(src) {
        var blob = new Blob([src], { type: 'application/javascript' });
        return new Worker(URL.createObjectURL(blob));
      });
  }

  function initEngine() {
    return fetchEngine().then(function(w) {
      return new Promise(function(resolve) {
        _worker = w;
        _worker.onmessage = function(e) {
          var msg = typeof e.data === 'string' ? e.data : '';
          if (msg === 'uciok') { _ready = true; resolve(); }
          if (_pending && msg.indexOf('bestmove') === 0) {
            var cb = _pending;
            _pending = null;
            cb(msg.split(' ')[1]);
          }
        };
        _worker.onerror = function() {
          _ready = false;
          if (_pending) { var cb = _pending; _pending = null; cb(null); }
        };
        _worker.postMessage('uci');
      });
    });
  }

  function getBestMove(fen, depth) {
    depth = depth || 12;
    return new Promise(function(resolve) {
      if (!_worker || !_ready) {
        initEngine().then(function() {
          _pending = resolve;
          _worker.postMessage('position fen ' + fen);
          _worker.postMessage('go depth ' + depth);
        }).catch(function() { resolve(null); });
      } else {
        _pending = resolve;
        _worker.postMessage('position fen ' + fen);
        _worker.postMessage('go depth ' + depth);
      }
    });
  }

  function getFEN() {
    var b = findBoard();
    if (!b) return null;
    if (b.game && b.game.getFEN) {
      try { var f = b.game.getFEN(); if (f) return f; } catch(e) {}
    }
    return null;
  }

  function getTurn() {
    var b = findBoard();
    if (!b) return 0;
    if (b.game && b.game.getTurn) {
      try { return b.game.getTurn(); } catch(e) {}
    }
    var last = document.querySelector('wc-simple-move-list .node.main-line-ply:last-of-type');
    if (!last) return 1;
    if (last.classList.contains('white-move')) return 2;
    if (last.classList.contains('black-move')) return 1;
    return 1;
  }

  function getPlayingAs() {
    var b = findBoard();
    if (!b) return 1;
    if (b.game && b.game.getPlayingAs) {
      try { return b.game.getPlayingAs(); } catch(e) {}
    }
    if (b.classList.contains('flipped') || b.hasAttribute('flipped')) return 2;
    return 1;
  }

  function isOurTurn() {
    return getTurn() === getPlayingAs();
  }

  function isGameOver() {
    var b = findBoard();
    if (b && b.game && b.game.isGameOver) {
      try { if (b.game.isGameOver()) return true; } catch(e) {}
    }
    if (b && b.state && b.state.isGameOver) return true;
    var sels = ['.game-over-modal', '.modal-game-over-component', '[data-cy="game-over-modal"]', '.game-result', '.game-over-header'];
    for (var i = 0; i < sels.length; i++) {
      if (document.querySelector(sels[i])) return true;
    }
    return false;
  }

  function isFlipped() {
    var b = findBoard();
    if (!b) return false;
    return b.classList.contains('flipped') || b.hasAttribute('flipped');
  }

  function pixelFor(file, rank) {
    var b = findBoard();
    if (!b) return null;
    var rect = b.getBoundingClientRect();
    var sq = rect.width / 8;
    var flip = isFlipped();
    var x = flip ? rect.left + (8 - file) * sq + sq / 2 : rect.left + (file - 1) * sq + sq / 2;
    var y = flip ? rect.top + (rank - 1) * sq + sq / 2 : rect.top + (8 - rank) * sq + sq / 2;
    return { x: x, y: y };
  }

  function fire(el, type, x, y) {
    el.dispatchEvent(new PointerEvent(type, {
      bubbles: true, cancelable: true, clientX: x, clientY: y,
      pointerId: 1, pointerType: 'mouse', button: 0,
      buttons: type === 'pointerdown' ? 1 : 0, view: window
    }));
  }

  function rand(a, b) { return Math.floor(Math.random() * (b - a + 1)) + a; }
  function sleep(ms) { return new Promise(function(r) { setTimeout(r, ms); }); }

  async function makeMove(uci) {
    var sf = uci.charCodeAt(0) - 96, sr = parseInt(uci[1]);
    var tf = uci.charCodeAt(2) - 96, tr = parseInt(uci[3]);
    var promo = uci.length > 4 ? uci[4] : null;

    var b = findBoard();
    if (!b) return false;

    var srcPx = pixelFor(sf, sr);
    var tgtPx = pixelFor(tf, tr);
    if (!srcPx || !tgtPx) return false;

    var piece = b.querySelector('.piece.square-' + sf + sr);
    var src = piece || document.elementFromPoint(srcPx.x, srcPx.y);
    if (!src) return false;

    fire(src, 'pointerdown', srcPx.x, srcPx.y);
    fire(src, 'pointerup', srcPx.x, srcPx.y);

    await sleep(5);

    var tgt = document.elementFromPoint(tgtPx.x, tgtPx.y);
    if (!tgt) return false;

    fire(tgt, 'pointerdown', tgtPx.x, tgtPx.y);
    fire(tgt, 'pointerup', tgtPx.x, tgtPx.y);

    if (promo) {
      await sleep(rand(80, 150));
      for (var i = 0; i < 10; i++) {
        var pm = { q: 'queen', r: 'rook', b: 'bishop', n: 'knight' };
        var sel = document.querySelector('[class*="promotion"] [class*="' + (pm[promo] || 'queen') + '"]');
        if (sel) {
          var pr = sel.getBoundingClientRect();
          fire(sel, 'pointerdown', pr.x + pr.width / 2, pr.y + pr.height / 2);
          fire(sel, 'pointerup', pr.x + pr.width / 2, pr.y + pr.height / 2);
          break;
        }
        await sleep(50);
      }
    }
    return true;
  }

  var _clickedRematch = false;
  async function clickRematch() {
    if (_clickedRematch) return;
    _clickedRematch = true;
    await sleep(500);
    var priority = ['nueva 1', 'nueva 3', 'nueva 5', 'nueva 10', 'new game', 'nueva partida', 'new 1', 'new 3', 'new 5', 'new 10', 'play again', 'jugar de nuevo', 'next'];
    var btns = document.querySelectorAll('button, [role="button"], a.ui_v5-button-component, .game-over-buttons-component button, .board-modal-container button, [data-cy="new-game-button"]');
    for (var p = 0; p < priority.length; p++) {
      for (var i = 0; i < btns.length; i++) {
        if ((btns[i].textContent || '').toLowerCase().trim().indexOf(priority[p]) !== -1) {
          btns[i].click();
          setTimeout(function() { _clickedRematch = false; }, 5000);
          return;
        }
      }
    }
    _clickedRematch = false;
  }

  async function tick() {
    if (!_active || _busy) return;
    _busy = true;
    try {
      if (isGameOver()) { await clickRematch(); _busy = false; return; }
      if (!isOurTurn()) { _busy = false; return; }
      var fen = getFEN();
      if (!fen) { _busy = false; return; }
      if (isGameOver()) { _busy = false; return; }
      var best = await getBestMove(fen, 8);
      if (!best || !_active) { _busy = false; return; }
      await makeMove(best);
    } catch(e) {}
    _busy = false;
  }

  function start() {
    _active = true;
    if (!_poll) _poll = setInterval(tick, 100);
    if (!_gameOverPoll) _gameOverPoll = setInterval(function() { if (_active && isGameOver()) clickRematch(); }, 3000);
    updateDot();
    tick();
  }

  function stop() {
    _active = false;
    if (_poll) { clearInterval(_poll); _poll = null; }
    if (_gameOverPoll) { clearInterval(_gameOverPoll); _gameOverPoll = null; }
    updateDot();
  }

  function toggle() { if (_active) stop(); else start(); }

  function createDot() {
    _dot = document.createElement('div');
    Object.assign(_dot.style, {
      position: 'fixed', bottom: '8px', right: '8px',
      width: '12px', height: '12px', borderRadius: '50%',
      background: 'rgba(128,128,128,0.5)', zIndex: '999999',
      cursor: 'pointer', transition: 'background 0.2s'
    });
    _dot.addEventListener('click', toggle);
    document.body.appendChild(_dot);
  }

  function updateDot() {
    if (!_dot) return;
    _dot.style.background = _active ? 'rgba(76,175,80,0.6)' : 'rgba(128,128,128,0.5)';
  }

  document.addEventListener('keydown', function(e) {
    if (e.altKey && e.key.toLowerCase() === 's') { e.preventDefault(); toggle(); }
  });

  CS.start = start;
  CS.stop = stop;
  CS.toggle = toggle;
  CS.getFEN = getFEN;
  CS.getBestMove = getBestMove;
  CS.makeMove = makeMove;

  createDot();
  initEngine().then(function() { start(); });
})();
