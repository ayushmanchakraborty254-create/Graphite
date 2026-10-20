/**
 * Graphite Web — Interactive WebApp Engine
 * Contains: Canvas Inking, E-Book/PDF Reader, Rich Text Boxes, Voice-to-Text,
 * 3:4 Cover Bookshelf, and Cloud Sync / Drive Simulation.
 */

(function () {
  'use strict';

  // ==========================================
  // 1. STATE & STORAGE
  // ==========================================
  const STORAGE_KEY_NOTEBOOKS = 'graphite_web_notebooks_v1';
  const STORAGE_KEY_ACTIVE_ID = 'graphite_web_active_nb_id';
  const STORAGE_KEY_BOOKMARKS = 'graphite_web_reader_bookmarks';

  // Sample starter notebooks if none exists
  const DEFAULT_NOTEBOOKS = [
    {
      id: 'nb-1',
      title: 'Physics & Quantum Mechanics',
      template: 'paper-ruled',
      cover: 'gradient-orange',
      createdAt: '2026-10-18T10:00:00Z',
      strokes: [],
      textBoxes: [
        {
          id: 'tb-1',
          x: 90,
          y: 70,
          width: 320,
          height: 110,
          content: '<b>Schrödinger Equation:</b><br>iℏ ∂/∂t Ψ(r,t) = Ĥ Ψ(r,t)<br><i>Lecture notes & wave packet derivation</i>'
        }
      ]
    },
    {
      id: 'nb-2',
      title: 'The Art of Focused Reading',
      template: 'paper-dotted',
      cover: 'gradient-blue',
      createdAt: '2026-10-19T14:30:00Z',
      strokes: [],
      textBoxes: []
    },
    {
      id: 'nb-3',
      title: 'Graphite Architecture & Sketches',
      template: 'paper-dark-ruled',
      cover: 'gradient-dark',
      createdAt: '2026-10-20T08:15:00Z',
      strokes: [],
      textBoxes: []
    }
  ];

  let notebooks = [];
  try {
    const raw = localStorage.getItem(STORAGE_KEY_NOTEBOOKS);
    notebooks = raw ? JSON.parse(raw) : DEFAULT_NOTEBOOKS;
  } catch (e) {
    notebooks = DEFAULT_NOTEBOOKS;
  }

  let activeNotebookId = localStorage.getItem(STORAGE_KEY_ACTIVE_ID) || notebooks[0].id;
  let activeNotebook = notebooks.find(n => n.id === activeNotebookId) || notebooks[0];

  function saveNotebooks() {
    try {
      localStorage.setItem(STORAGE_KEY_NOTEBOOKS, JSON.stringify(notebooks));
      localStorage.setItem(STORAGE_KEY_ACTIVE_ID, activeNotebookId);
      updateSyncStatus('Saved locally & cloud sync ready');
    } catch (e) {
      console.warn('LocalStorage save failed:', e);
    }
  }

  // ==========================================
  // 2. CANVAS INKING ENGINE
  // ==========================================
  const canvas = document.getElementById('graphiteCanvas');
  const canvasContainer = document.getElementById('canvasContainer');
  const textBoxLayer = document.getElementById('textBoxLayer');
  let ctx = canvas ? canvas.getContext('2d') : null;

  let currentTool = 'pen'; // 'pen', 'fountain', 'highlighter', 'precision-eraser', 'stroke-eraser', 'text'
  let currentColor = '#121418';
  let strokeWidth = 3;
  let isDrawing = false;
  let currentStroke = null;
  let undoStack = [];
  let redoStack = [];

  // Point smoothing variables
  let lastX = 0;
  let lastY = 0;
  let lastTime = 0;
  let lastWidth = strokeWidth;

  function initCanvas() {
    if (!canvas || !canvasContainer) return;

    // Retina High-DPI Canvas resize
    function resizeCanvas() {
      const rect = canvasContainer.getBoundingClientRect();
      const dpr = window.devicePixelRatio || 1;
      
      canvas.width = rect.width * dpr;
      canvas.height = rect.height * dpr;
      ctx.scale(dpr, dpr);
      
      redrawCanvas();
    }

    window.addEventListener('resize', resizeCanvas);
    resizeCanvas();

    // Attach Pointer Events
    canvas.addEventListener('pointerdown', handlePointerDown);
    canvas.addEventListener('pointermove', handlePointerMove);
    canvas.addEventListener('pointerup', handlePointerUp);
    canvas.addEventListener('pointercancel', handlePointerUp);
    canvas.addEventListener('pointerout', handlePointerUp);

    // Initial strokes from active notebook
    if (activeNotebook && activeNotebook.strokes) {
      undoStack = [...activeNotebook.strokes];
      redrawCanvas();
    }
  }

  function handlePointerDown(e) {
    if (currentTool === 'text') {
      const rect = canvas.getBoundingClientRect();
      createTextBox(e.clientX - rect.left, e.clientY - rect.top, 'Type note here...');
      selectTool('pen');
      return;
    }

    isDrawing = true;
    canvas.setPointerCapture(e.pointerId);

    const rect = canvas.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;
    const pressure = e.pressure > 0 ? e.pressure : 0.5;

    lastX = x;
    lastY = y;
    lastTime = Date.now();
    lastWidth = strokeWidth;

    if (currentTool === 'stroke-eraser') {
      eraseStrokeAt(x, y);
      return;
    }

    currentStroke = {
      id: 'stroke-' + Date.now() + '-' + Math.random().toString(36).substr(2, 5),
      tool: currentTool,
      color: currentColor,
      baseWidth: strokeWidth,
      points: [{ x, y, pressure, time: lastTime }]
    };

    // Draw initial dot
    ctx.save();
    if (currentTool === 'highlighter') {
      ctx.globalAlpha = 0.35;
      ctx.fillStyle = currentColor;
      ctx.beginPath();
      ctx.arc(x, y, strokeWidth * 2.5, 0, Math.PI * 2);
      ctx.fill();
    } else if (currentTool === 'precision-eraser') {
      ctx.globalCompositeOperation = 'destination-out';
      ctx.beginPath();
      ctx.arc(x, y, strokeWidth * 2, 0, Math.PI * 2);
      ctx.fill();
    } else {
      ctx.fillStyle = currentColor;
      ctx.beginPath();
      ctx.arc(x, y, strokeWidth / 2, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.restore();
  }

  function handlePointerMove(e) {
    if (!isDrawing) return;

    const rect = canvas.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;
    const now = Date.now();
    const pressure = e.pressure > 0 ? e.pressure : 0.5;

    if (currentTool === 'stroke-eraser') {
      eraseStrokeAt(x, y);
      return;
    }

    if (!currentStroke) return;

    currentStroke.points.push({ x, y, pressure, time: now });

    ctx.save();
    if (currentTool === 'highlighter') {
      ctx.globalAlpha = 0.32;
      ctx.strokeStyle = currentColor;
      ctx.lineWidth = strokeWidth * 3.5;
      ctx.lineCap = 'square';
      ctx.lineJoin = 'bevel';

      ctx.beginPath();
      ctx.moveTo(lastX, lastY);
      ctx.lineTo(x, y);
      ctx.stroke();
    } else if (currentTool === 'precision-eraser') {
      ctx.globalCompositeOperation = 'destination-out';
      ctx.lineWidth = strokeWidth * 3;
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';

      ctx.beginPath();
      ctx.moveTo(lastX, lastY);
      ctx.lineTo(x, y);
      ctx.stroke();
    } else if (currentTool === 'fountain') {
      // Velocity & pressure-sensitive fountain calligraphy
      const dist = Math.hypot(x - lastX, y - lastY);
      const timeDiff = Math.max(now - lastTime, 1);
      const speed = dist / timeDiff;

      // Faster = thinner, slower = thicker, modified by pressure
      const targetWidth = Math.max(strokeWidth * 0.4, (strokeWidth * 1.6 - speed * 0.8) * (pressure * 1.4));
      const smoothedWidth = lastWidth * 0.7 + targetWidth * 0.3;

      ctx.strokeStyle = currentColor;
      ctx.lineWidth = smoothedWidth;
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';

      ctx.beginPath();
      ctx.moveTo(lastX, lastY);
      ctx.lineTo(x, y);
      ctx.stroke();

      lastWidth = smoothedWidth;
    } else {
      // Standard Crisp Ballpoint Pen
      ctx.strokeStyle = currentColor;
      ctx.lineWidth = strokeWidth;
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';

      ctx.beginPath();
      ctx.moveTo(lastX, lastY);
      ctx.lineTo(x, y);
      ctx.stroke();
    }
    ctx.restore();

    lastX = x;
    lastY = y;
    lastTime = now;
  }

  function handlePointerUp(e) {
    if (!isDrawing) return;
    isDrawing = false;

    if (currentStroke && currentStroke.points.length > 0) {
      undoStack.push(currentStroke);
      redoStack = []; // Clear redo
      activeNotebook.strokes = [...undoStack];
      saveNotebooks();
    }
    currentStroke = null;
    updateUndoRedoButtons();
  }

  // Redraw all strokes in undo stack
  function redrawCanvas() {
    if (!ctx || !canvas) return;
    const rect = canvasContainer.getBoundingClientRect();
    ctx.clearRect(0, 0, rect.width, rect.height);

    undoStack.forEach(stroke => {
      if (!stroke.points || stroke.points.length === 0) return;
      ctx.save();

      if (stroke.tool === 'highlighter') {
        ctx.globalAlpha = 0.32;
        ctx.strokeStyle = stroke.color;
        ctx.lineWidth = stroke.baseWidth * 3.5;
        ctx.lineCap = 'square';
        ctx.lineJoin = 'bevel';
      } else if (stroke.tool === 'precision-eraser') {
        ctx.globalCompositeOperation = 'destination-out';
        ctx.lineWidth = stroke.baseWidth * 3;
        ctx.lineCap = 'round';
        ctx.lineJoin = 'round';
      } else {
        ctx.strokeStyle = stroke.color;
        ctx.lineWidth = stroke.baseWidth;
        ctx.lineCap = 'round';
        ctx.lineJoin = 'round';
      }

      ctx.beginPath();
      ctx.moveTo(stroke.points[0].x, stroke.points[0].y);
      for (let i = 1; i < stroke.points.length; i++) {
        ctx.lineTo(stroke.points[i].x, stroke.points[i].y);
      }
      ctx.stroke();
      ctx.restore();
    });
  }

  // Vector stroke eraser: delete stroke if clicked/dragged near any of its points
  function eraseStrokeAt(x, y) {
    const threshold = 16;
    const initialLen = undoStack.length;

    undoStack = undoStack.filter(stroke => {
      return !stroke.points.some(p => Math.hypot(p.x - x, p.y - y) < threshold);
    });

    if (undoStack.length !== initialLen) {
      activeNotebook.strokes = [...undoStack];
      saveNotebooks();
      redrawCanvas();
      updateUndoRedoButtons();
    }
  }

  function undo() {
    if (undoStack.length === 0) return;
    const popped = undoStack.pop();
    redoStack.push(popped);
    activeNotebook.strokes = [...undoStack];
    saveNotebooks();
    redrawCanvas();
    updateUndoRedoButtons();
  }

  function redo() {
    if (redoStack.length === 0) return;
    const item = redoStack.pop();
    undoStack.push(item);
    activeNotebook.strokes = [...undoStack];
    saveNotebooks();
    redrawCanvas();
    updateUndoRedoButtons();
  }

  function clearCanvas() {
    if (undoStack.length === 0 && (!activeNotebook.textBoxes || activeNotebook.textBoxes.length === 0)) return;
    if (confirm('Clear entire page notes and strokes?')) {
      undoStack = [];
      redoStack = [];
      activeNotebook.strokes = [];
      activeNotebook.textBoxes = [];
      textBoxLayer.innerHTML = '';
      saveNotebooks();
      redrawCanvas();
      updateUndoRedoButtons();
    }
  }

  function updateUndoRedoButtons() {
    const undoBtn = document.getElementById('btnUndo');
    const redoBtn = document.getElementById('btnRedo');
    if (undoBtn) undoBtn.disabled = undoStack.length === 0;
    if (redoBtn) redoBtn.disabled = redoStack.length === 0;
  }

  // ==========================================
  // 3. DYNAMIC MOVABLE & RESIZABLE TEXT BOXES
  // ==========================================
  function createTextBox(x, y, content = 'Type notes here...', id = null, width = 220, height = 90) {
    const boxId = id || 'tb-' + Date.now();
    const box = document.createElement('div');
    box.className = 'canvas-text-box';
    box.id = boxId;
    box.style.left = `${Math.max(10, x)}px`;
    box.style.top = `${Math.max(10, y)}px`;
    box.style.width = `${width}px`;
    box.style.minHeight = `${height}px`;

    box.innerHTML = `
      <div class="text-box-header">
        <span>⠿ Drag note</span>
        <button class="text-box-delete-btn" title="Delete text card">✕</button>
      </div>
      <div class="text-box-content" contenteditable="true">${content}</div>
      <div class="text-box-resize-handle"></div>
    `;

    // Make Draggable via header
    const header = box.querySelector('.text-box-header');
    let isDraggingBox = false;
    let startX, startY, initialLeft, initialTop;

    header.addEventListener('mousedown', (e) => {
      isDraggingBox = true;
      startX = e.clientX;
      startY = e.clientY;
      initialLeft = box.offsetLeft;
      initialTop = box.offsetTop;
      e.stopPropagation();

      function onMouseMove(ev) {
        if (!isDraggingBox) return;
        const dx = ev.clientX - startX;
        const dy = ev.clientY - startY;
        box.style.left = `${Math.max(5, initialLeft + dx)}px`;
        box.style.top = `${Math.max(5, initialTop + dy)}px`;
      }

      function onMouseUp() {
        if (isDraggingBox) {
          isDraggingBox = false;
          saveTextBoxesState();
        }
        window.removeEventListener('mousemove', onMouseMove);
        window.removeEventListener('mouseup', onMouseUp);
      }

      window.addEventListener('mousemove', onMouseMove);
      window.addEventListener('mouseup', onMouseUp);
    });

    // Make Resizable via bottom-right handle
    const resizeHandle = box.querySelector('.text-box-resize-handle');
    let isResizing = false;
    let startW, startH;

    resizeHandle.addEventListener('mousedown', (e) => {
      isResizing = true;
      startX = e.clientX;
      startY = e.clientY;
      startW = box.offsetWidth;
      startH = box.offsetHeight;
      e.stopPropagation();

      function onResizeMove(ev) {
        if (!isResizing) return;
        box.style.width = `${Math.max(120, startW + (ev.clientX - startX))}px`;
        box.style.minHeight = `${Math.max(50, startH + (ev.clientY - startY))}px`;
      }

      function onResizeUp() {
        if (isResizing) {
          isResizing = false;
          saveTextBoxesState();
        }
        window.removeEventListener('mousemove', onResizeMove);
        window.removeEventListener('mouseup', onResizeUp);
      }

      window.addEventListener('mousemove', onResizeMove);
      window.addEventListener('mouseup', onResizeUp);
    });

    // Delete Button
    box.querySelector('.text-box-delete-btn').addEventListener('click', (e) => {
      e.stopPropagation();
      box.remove();
      saveTextBoxesState();
    });

    // Content change auto-save
    const contentDiv = box.querySelector('.text-box-content');
    contentDiv.addEventListener('input', () => {
      saveTextBoxesState();
    });

    textBoxLayer.appendChild(box);
    saveTextBoxesState();

    // Focus if newly spawned
    if (!id) {
      contentDiv.focus();
    }
  }

  function saveTextBoxesState() {
    if (!activeNotebook) return;
    const boxes = [];
    document.querySelectorAll('.canvas-text-box').forEach(el => {
      boxes.push({
        id: el.id,
        x: el.offsetLeft,
        y: el.offsetTop,
        width: el.offsetWidth,
        height: el.offsetHeight,
        content: el.querySelector('.text-box-content').innerHTML
      });
    });
    activeNotebook.textBoxes = boxes;
    saveNotebooks();
  }

  function loadTextBoxesState() {
    textBoxLayer.innerHTML = '';
    if (activeNotebook && activeNotebook.textBoxes) {
      activeNotebook.textBoxes.forEach(b => {
        createTextBox(b.x, b.y, b.content, b.id, b.width, b.height);
      });
    }
  }

  // ==========================================
  // 4. SMART VOICE-TO-TEXT DICTATION
  // ==========================================
  let speechRecognizer = null;
  let isListening = false;

  function initVoiceDictation() {
    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    const voiceBtn = document.getElementById('btnVoiceDictate');
    if (!voiceBtn) return;

    if (!SpeechRecognition) {
      voiceBtn.title = 'Web Speech API not supported in this browser. Please use Chrome/Edge for voice dictation.';
      return;
    }

    speechRecognizer = new SpeechRecognition();
    speechRecognizer.continuous = true;
    speechRecognizer.interimResults = true;
    speechRecognizer.lang = 'en-US';

    speechRecognizer.onstart = () => {
      isListening = true;
      voiceBtn.classList.add('listening');
      updateSyncStatus('🎙️ Listening... speak clearly');
    };

    speechRecognizer.onresult = (event) => {
      let finalTranscript = '';
      for (let i = event.resultIndex; i < event.results.length; ++i) {
        if (event.results[i].isFinal) {
          finalTranscript += event.results[i][0].transcript;
        }
      }

      if (finalTranscript.trim()) {
        // Smart sentence capitalization
        const formatted = formatTranscript(finalTranscript.trim());
        // Append to an active text box or create new
        const activeBox = document.querySelector('.canvas-text-box:last-child .text-box-content');
        if (activeBox) {
          activeBox.innerHTML += ' ' + formatted;
        } else {
          createTextBox(120, 160, `🎙️ <i>Dictated:</i> ${formatted}`);
        }
        saveTextBoxesState();
      }
    };

    speechRecognizer.onerror = (err) => {
      console.warn('Speech recognition error:', err);
      stopListening();
    };

    speechRecognizer.onend = () => {
      stopListening();
    };

    voiceBtn.addEventListener('click', () => {
      if (isListening) {
        stopListening();
      } else {
        startListening();
      }
    });
  }

  function startListening() {
    if (!speechRecognizer) {
      alert('Speech recognition is not supported in this browser. Please try Chrome, Edge, or Safari.');
      return;
    }
    try {
      speechRecognizer.start();
    } catch (e) {
      console.error(e);
    }
  }

  function stopListening() {
    isListening = false;
    const voiceBtn = document.getElementById('btnVoiceDictate');
    if (voiceBtn) voiceBtn.classList.remove('listening');
    if (speechRecognizer) {
      try { speechRecognizer.stop(); } catch (e) {}
    }
    updateSyncStatus('Voice transcription complete');
  }

  function formatTranscript(text) {
    if (!text) return '';
    return text.charAt(0).toUpperCase() + text.slice(1) + '.';
  }

  // ==========================================
  // 5. E-BOOK & PDF READER ENGINE
  // ==========================================
  const BOOK_CHAPTERS = [
    {
      title: 'Chapter 1: The Philosophy of Focused Reading',
      subtitle: 'Designing for Deep Immersion and Zero Distraction',
      content: `
        <p class="reader-paragraph">
          In an era characterized by hyper-fragmented attention spans and relentless notifications, reading a full-length book or comprehensive research paper has become an act of conscious resistance. When developing Graphite, our founding mission was not merely to assemble another digital viewer, but to engineer an ergonomic sanctuary for the human intellect.
        </p>
        <blockquote class="reader-quote">
          "A great reader does not consume words; they converse with the author across time. The screen must dissolve, leaving only thoughts."
        </blockquote>
        <p class="reader-paragraph">
          Digital reading interfaces often fail because they impose mechanical friction: slow page turns, harsh blue-light contrasts, intrusive floating menus, and poorly scaled typography. Graphite remedies this through adaptive color temperatures (Day, Vintage Sepia, and OLED Night modes) paired with fine typographical rhythm.
        </p>
      `
    },
    {
      title: 'Chapter 2: Sub-Millisecond Inking & Stylus Dynamics',
      subtitle: 'The Engineering Behind Natural Handwriting',
      content: `
        <p class="reader-paragraph">
          The human hand is one of the most sophisticated biomechanical instruments on earth. When an artist or mathematician touches a stylus to a tablet, the brain expects immediate tactile visual feedback. A latency greater than 16 milliseconds registers unconsciously as lag, degrading cognitive flow.
        </p>
        <p class="reader-paragraph">
          Graphite utilizes predictive Bezier curve smoothing combined with hardware-accelerated 2D pipeline rendering. Velocity and pressure sensitivity are calculated dynamically: a rapid stroke tapers naturally like a Japanese calligraphy fountain pen, while a deliberate stroke provides the firm, authoritative line of a dense graphite pencil.
        </p>
        <blockquote class="reader-quote">
          "Handwriting activates deeper memory consolidation circuits in the brain than mechanical keyboard typing."
        </blockquote>
      `
    },
    {
      title: 'Chapter 3: Privacy-First Local Cloud Synchronization',
      subtitle: 'Empowering Sovereignty Over Personal Knowledge',
      content: `
        <p class="reader-paragraph">
          Your personal notebooks contain your most intimate thoughts, unformed hypotheses, research insights, and creative breakthroughs. In Graphite, we believe that software must honor user sovereignty by default.
        </p>
        <p class="reader-paragraph">
          Our architecture is local-first: every stroke, annotation, and bookmark is stored encrypted on your device's isolated sandboxed storage. When cloud synchronization is requested, it communicates via restricted sandboxed scopes—such as Google Drive's <code>drive.file</code> scope—meaning the application can never inspect or alter any document outside of what you explicitly author within Graphite.
        </p>
      `
    }
  ];

  let currentChapterIdx = 0;
  let readerFontSize = 18;
  let isSerifFont = true;

  function initReader() {
    renderChapter();

    // Theme Switchers
    document.querySelectorAll('.theme-pill-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        document.querySelectorAll('.theme-pill-btn').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');

        const readerElem = document.getElementById('readerContainer');
        readerElem.className = 'reader-container theme-' + btn.getAttribute('data-theme');
      });
    });

    // Font Size Controls
    const btnFontMinus = document.getElementById('btnFontMinus');
    const btnFontPlus = document.getElementById('btnFontPlus');
    const readerView = document.getElementById('readerBookView');

    if (btnFontMinus && btnFontPlus && readerView) {
      btnFontMinus.addEventListener('click', () => {
        readerFontSize = Math.max(14, readerFontSize - 2);
        readerView.style.fontSize = `${readerFontSize}px`;
      });
      btnFontPlus.addEventListener('click', () => {
        readerFontSize = Math.min(28, readerFontSize + 2);
        readerView.style.fontSize = `${readerFontSize}px`;
      });
    }

    // Font Toggle (Serif vs Sans)
    const btnFontToggle = document.getElementById('btnFontToggle');
    if (btnFontToggle && readerView) {
      btnFontToggle.addEventListener('click', () => {
        isSerifFont = !isSerifFont;
        if (isSerifFont) {
          readerView.classList.remove('font-sans');
          readerView.classList.add('font-serif');
          btnFontToggle.textContent = 'Serif';
        } else {
          readerView.classList.remove('font-serif');
          readerView.classList.add('font-sans');
          btnFontToggle.textContent = 'Sans-Serif';
        }
      });
    }

    // Page Navigation
    const btnPrev = document.getElementById('btnPrevPage');
    const btnNext = document.getElementById('btnNextPage');
    if (btnPrev && btnNext) {
      btnPrev.addEventListener('click', () => {
        if (currentChapterIdx > 0) {
          currentChapterIdx--;
          renderChapter();
        }
      });
      btnNext.addEventListener('click', () => {
        if (currentChapterIdx < BOOK_CHAPTERS.length - 1) {
          currentChapterIdx++;
          renderChapter();
        }
      });
    }

    // Chapter Select Dropdown
    const chapterSelect = document.getElementById('chapterSelect');
    if (chapterSelect) {
      chapterSelect.innerHTML = BOOK_CHAPTERS.map((ch, idx) => `
        <option value="${idx}">${ch.title.split(':')[0]}</option>
      `).join('');
      chapterSelect.addEventListener('change', (e) => {
        currentChapterIdx = parseInt(e.target.value, 10);
        renderChapter();
      });
    }

    // Upload custom document (.pdf or .txt)
    const docUploadInput = document.getElementById('docUploadInput');
    if (docUploadInput) {
      docUploadInput.addEventListener('change', (e) => {
        const file = e.target.files[0];
        if (!file) return;

        if (file.type === 'application/pdf' || file.name.endsWith('.pdf')) {
          renderPdfDocument(file);
        } else {
          // Read text file
          const reader = new FileReader();
          reader.onload = (event) => {
            BOOK_CHAPTERS.push({
              title: `Imported: ${file.name}`,
              subtitle: `Imported on ${new Date().toLocaleDateString()}`,
              content: `<div class="reader-paragraph">${event.target.result.replace(/\n\n/g, '</p><p class="reader-paragraph">')}</div>`
            });
            currentChapterIdx = BOOK_CHAPTERS.length - 1;
            renderChapter();
            alert(`Document "${file.name}" imported into reader!`);
          };
          reader.readAsText(file);
        }
      });
    }
  }

  function renderChapter() {
    const ch = BOOK_CHAPTERS[currentChapterIdx];
    const titleEl = document.getElementById('readerChapterTitle');
    const subEl = document.getElementById('readerChapterSub');
    const bodyEl = document.getElementById('readerChapterBody');
    const progressFill = document.getElementById('readerProgressFill');
    const progressText = document.getElementById('readerProgressText');
    const chapterSelect = document.getElementById('chapterSelect');

    if (titleEl) titleEl.textContent = ch.title;
    if (subEl) subEl.textContent = ch.subtitle;
    if (bodyEl) bodyEl.innerHTML = ch.content;

    const progressPercent = Math.round(((currentChapterIdx + 1) / BOOK_CHAPTERS.length) * 100);
    if (progressFill) progressFill.style.width = `${progressPercent}%`;
    if (progressText) progressText.textContent = `${progressPercent}% (${currentChapterIdx + 1}/${BOOK_CHAPTERS.length})`;

    if (chapterSelect) chapterSelect.value = currentChapterIdx;

    const btnPrev = document.getElementById('btnPrevPage');
    const btnNext = document.getElementById('btnNextPage');
    if (btnPrev) btnPrev.disabled = currentChapterIdx === 0;
    if (btnNext) btnNext.disabled = currentChapterIdx === BOOK_CHAPTERS.length - 1;
  }

  function renderPdfDocument(file) {
    const fileUrl = URL.createObjectURL(file);
    const bodyEl = document.getElementById('readerChapterBody');
    const titleEl = document.getElementById('readerChapterTitle');
    const subEl = document.getElementById('readerChapterSub');

    if (titleEl) titleEl.textContent = file.name;
    if (subEl) subEl.textContent = `PDF Document (${(file.size / (1024 * 1024)).toFixed(2)} MB)`;

    if (bodyEl) {
      bodyEl.innerHTML = `
        <div style="text-align:center; padding: 2rem;">
          <p style="margin-bottom: 1.5rem;">📄 Native PDF Viewer ready. You can read, view and scroll below:</p>
          <iframe src="${fileUrl}" style="width: 100%; height: 600px; border: 1px solid var(--reader-border); border-radius: 8px;"></iframe>
        </div>
      `;
    }
  }

  // ==========================================
  // 6. BOOKSHELF & 3:4 COVER GALLERY
  // ==========================================
  const COVER_PRESETS = [
    { id: 'gradient-orange', name: 'Sunrise Ember', bg: 'linear-gradient(145deg, #ff5e1e, #b83200)' },
    { id: 'gradient-blue', name: 'Cobalt Deep', bg: 'linear-gradient(145deg, #1e3a8a, #0f172a)' },
    { id: 'gradient-dark', name: 'Midnight Obsidian', bg: 'linear-gradient(145deg, #18181b, #09090b)' },
    { id: 'gradient-emerald', name: 'Forest Velvet', bg: 'linear-gradient(145deg, #065f46, #022c22)' },
    { id: 'gradient-purple', name: 'Cyber Violet', bg: 'linear-gradient(145deg, #7c3aed, #4c1d95)' },
    { id: 'gradient-amber', name: 'Vintage Ochre', bg: 'linear-gradient(145deg, #b45309, #78350f)' }
  ];

  function renderBookshelf() {
    const grid = document.getElementById('bookshelfGrid');
    if (!grid) return;

    grid.innerHTML = '';

    notebooks.forEach(nb => {
      const preset = COVER_PRESETS.find(p => p.id === nb.cover) || COVER_PRESETS[0];
      const card = document.createElement('div');
      card.className = 'notebook-card';
      card.innerHTML = `
        <div class="notebook-cover-34" style="background: ${preset.bg}">
          <div class="notebook-spine"></div>
          <span class="cover-tag">${nb.template.replace('paper-', '').toUpperCase()}</span>
          <h3 class="cover-title">${nb.title}</h3>
        </div>
        <div class="notebook-meta">
          <div class="notebook-name">${nb.title}</div>
          <div class="notebook-sub">${nb.strokes ? nb.strokes.length : 0} strokes • ${new Date(nb.createdAt).toLocaleDateString()}</div>
        </div>
      `;

      card.addEventListener('click', () => {
        selectNotebook(nb.id);
        switchStudioTab('canvas');
      });

      grid.appendChild(card);
    });

    // Add "+ New Notebook" card
    const addCard = document.createElement('div');
    addCard.className = 'add-notebook-card';
    addCard.innerHTML = `
      <div class="add-icon-circle">+</div>
      <span style="font-weight: 600;">Create Notebook</span>
    `;
    addCard.addEventListener('click', openNewNotebookModal);
    grid.appendChild(addCard);
  }

  function selectNotebook(id) {
    activeNotebookId = id;
    activeNotebook = notebooks.find(n => n.id === id) || notebooks[0];
    localStorage.setItem(STORAGE_KEY_ACTIVE_ID, activeNotebookId);

    // Update Title Input
    const titleInput = document.getElementById('notebookTitleInput');
    if (titleInput) titleInput.value = activeNotebook.title;

    // Update Paper Template
    setPaperTemplate(activeNotebook.template || 'paper-blank');

    // Update Canvas Strokes & Text Boxes
    undoStack = activeNotebook.strokes ? [...activeNotebook.strokes] : [];
    redoStack = [];
    redrawCanvas();
    loadTextBoxesState();
    updateUndoRedoButtons();
  }

  // ==========================================
  // 7. NEW NOTEBOOK MODAL
  // ==========================================
  let selectedCoverPreset = 'gradient-orange';

  function initNewNotebookModal() {
    const modal = document.getElementById('newNotebookModal');
    const closeBtn = document.getElementById('closeModalBtn');
    const cancelBtn = document.getElementById('cancelModalBtn');
    const createBtn = document.getElementById('createNotebookConfirmBtn');
    const coverGrid = document.getElementById('coverSelectGrid');

    if (!modal) return;

    if (coverGrid) {
      coverGrid.innerHTML = COVER_PRESETS.map(preset => `
        <div class="cover-choice ${preset.id === selectedCoverPreset ? 'selected' : ''}" 
             data-id="${preset.id}" 
             style="background: ${preset.bg};" 
             title="${preset.name}"></div>
      `).join('');

      coverGrid.querySelectorAll('.cover-choice').forEach(el => {
        el.addEventListener('click', () => {
          coverGrid.querySelectorAll('.cover-choice').forEach(c => c.classList.remove('selected'));
          el.classList.add('selected');
          selectedCoverPreset = el.getAttribute('data-id');
        });
      });
    }

    function closeModal() {
      modal.classList.remove('open');
    }

    if (closeBtn) closeBtn.addEventListener('click', closeModal);
    if (cancelBtn) cancelBtn.addEventListener('click', closeModal);

    if (createBtn) {
      createBtn.addEventListener('click', () => {
        const titleInput = document.getElementById('newNotebookTitleInput');
        const templateSelect = document.getElementById('newNotebookTemplateSelect');
        const title = (titleInput && titleInput.value.trim()) || 'Untitled Notebook';
        const template = (templateSelect && templateSelect.value) || 'paper-ruled';

        const newNb = {
          id: 'nb-' + Date.now(),
          title: title,
          template: template,
          cover: selectedCoverPreset,
          createdAt: new Date().toISOString(),
          strokes: [],
          textBoxes: []
        };

        notebooks.unshift(newNb);
        saveNotebooks();
        renderBookshelf();
        selectNotebook(newNb.id);
        closeModal();
        switchStudioTab('canvas');
      });
    }
  }

  function openNewNotebookModal() {
    const modal = document.getElementById('newNotebookModal');
    if (modal) modal.classList.add('open');
  }

  // ==========================================
  // 8. CLOUD SYNC & DRIVE BACKUP SIMULATION
  // ==========================================
  function initCloudSyncManager() {
    const exportBtn = document.getElementById('btnExportBackup');
    const importInput = document.getElementById('importBackupInput');
    const resetBtn = document.getElementById('btnResetSampleData');

    if (exportBtn) {
      exportBtn.addEventListener('click', () => {
        const dataStr = "data:text/json;charset=utf-8," + encodeURIComponent(JSON.stringify(notebooks, null, 2));
        const dlAnchor = document.createElement('a');
        dlAnchor.setAttribute("href", dataStr);
        dlAnchor.setAttribute("download", `graphite_backup_${new Date().toISOString().slice(0, 10)}.json`);
        document.body.appendChild(dlAnchor);
        dlAnchor.click();
        dlAnchor.remove();
        updateSyncStatus('Backup exported successfully');
      });
    }

    if (importInput) {
      importInput.addEventListener('change', (e) => {
        const file = e.target.files[0];
        if (!file) return;
        const reader = new FileReader();
        reader.onload = (event) => {
          try {
            const imported = JSON.parse(event.target.result);
            if (Array.isArray(imported)) {
              notebooks = imported;
              saveNotebooks();
              renderBookshelf();
              selectNotebook(notebooks[0].id);
              alert('Backup restored successfully!');
            } else {
              alert('Invalid backup file format.');
            }
          } catch (err) {
            alert('Failed to parse backup JSON.');
          }
        };
        reader.readAsText(file);
      });
    }

    if (resetBtn) {
      resetBtn.addEventListener('click', () => {
        if (confirm('Reset to initial sample notebooks?')) {
          notebooks = DEFAULT_NOTEBOOKS;
          saveNotebooks();
          renderBookshelf();
          selectNotebook(notebooks[0].id);
        }
      });
    }
  }

  function updateSyncStatus(msg) {
    const statusText = document.getElementById('syncStatusText');
    if (statusText) statusText.textContent = msg;
  }

  // ==========================================
  // 9. TOOLBAR & APP CONTROLS WIRING
  // ==========================================
  function selectTool(toolName) {
    currentTool = toolName;
    document.querySelectorAll('.tool-btn[data-tool]').forEach(btn => {
      if (btn.getAttribute('data-tool') === toolName) {
        btn.classList.add('active');
      } else {
        btn.classList.remove('active');
      }
    });

    // Update cursor
    if (canvas) {
      if (toolName === 'stroke-eraser' || toolName === 'precision-eraser') {
        canvas.style.cursor = 'cell';
      } else if (toolName === 'text') {
        canvas.style.cursor = 'text';
      } else {
        canvas.style.cursor = 'crosshair';
      }
    }
  }

  function setPaperTemplate(templateName) {
    if (!canvasContainer) return;
    canvasContainer.className = `canvas-container ${templateName}`;
    const sel = document.getElementById('paperSelect');
    if (sel) sel.value = templateName;
    if (activeNotebook) {
      activeNotebook.template = templateName;
      saveNotebooks();
    }
  }

  function switchStudioTab(tabId) {
    document.querySelectorAll('.studio-tab-btn').forEach(b => {
      b.classList.toggle('active', b.getAttribute('data-tab') === tabId);
    });

    document.querySelectorAll('.studio-view').forEach(view => {
      view.classList.toggle('active', view.id === `${tabId}View`);
    });

    if (tabId === 'canvas') {
      redrawCanvas();
    } else if (tabId === 'bookshelf') {
      renderBookshelf();
    }
  }

  function initUI() {
    // Tool buttons click
    document.querySelectorAll('.tool-btn[data-tool]').forEach(btn => {
      btn.addEventListener('click', () => {
        selectTool(btn.getAttribute('data-tool'));
      });
    });

    // Color swatches click
    document.querySelectorAll('.color-swatch').forEach(swatch => {
      swatch.addEventListener('click', () => {
        document.querySelectorAll('.color-swatch').forEach(s => s.classList.remove('active'));
        swatch.classList.add('active');
        currentColor = swatch.getAttribute('data-color');
        updateStrokePreview();
      });
    });

    // Custom Color Input
    const customColorInput = document.getElementById('customColorInput');
    if (customColorInput) {
      customColorInput.addEventListener('input', (e) => {
        currentColor = e.target.value;
        document.querySelectorAll('.color-swatch').forEach(s => s.classList.remove('active'));
        updateStrokePreview();
      });
    }

    // Stroke width slider
    const strokeSlider = document.getElementById('strokeWidthSlider');
    if (strokeSlider) {
      strokeSlider.addEventListener('input', (e) => {
        strokeWidth = parseInt(e.target.value, 10);
        updateStrokePreview();
      });
    }

    function updateStrokePreview() {
      const dot = document.getElementById('strokePreviewDot');
      if (dot) {
        dot.style.width = `${Math.min(18, Math.max(4, strokeWidth))}px`;
        dot.style.height = `${Math.min(18, Math.max(4, strokeWidth))}px`;
        dot.style.backgroundColor = currentColor;
      }
    }

    // Paper template dropdown
    const paperSelect = document.getElementById('paperSelect');
    if (paperSelect) {
      paperSelect.addEventListener('change', (e) => {
        setPaperTemplate(e.target.value);
      });
    }

    // Undo / Redo / Clear
    const btnUndo = document.getElementById('btnUndo');
    const btnRedo = document.getElementById('btnRedo');
    const btnClear = document.getElementById('btnClearCanvas');
    if (btnUndo) btnUndo.addEventListener('click', undo);
    if (btnRedo) btnRedo.addEventListener('click', redo);
    if (btnClear) btnClear.addEventListener('click', clearCanvas);

    // Keyboard Shortcuts (Ctrl+Z, Ctrl+Y)
    window.addEventListener('keydown', (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'z') {
        if (e.shiftKey) {
          redo();
        } else {
          undo();
        }
      } else if ((e.ctrlKey || e.metaKey) && e.key === 'y') {
        redo();
      }
    });

    // Export PNG
    const btnExportPng = document.getElementById('btnExportPng');
    if (btnExportPng) {
      btnExportPng.addEventListener('click', () => {
        const link = document.createElement('a');
        link.download = `${activeNotebook.title.toLowerCase().replace(/\s+/g, '_')}_note.png`;
        link.href = canvas.toDataURL('image/png');
        link.click();
      });
    }

    // Export PDF / Print
    const btnExportPdf = document.getElementById('btnExportPdf');
    if (btnExportPdf) {
      btnExportPdf.addEventListener('click', () => {
        window.print();
      });
    }

    // Studio Tabs
    document.querySelectorAll('.studio-tab-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        switchStudioTab(btn.getAttribute('data-tab'));
      });
    });

    // Fullscreen Toggle
    const btnFullscreen = document.getElementById('btnFullscreenToggle');
    const studioWrapper = document.getElementById('studioWrapper');
    if (btnFullscreen && studioWrapper) {
      btnFullscreen.addEventListener('click', () => {
        studioWrapper.classList.toggle('is-fullscreen');
        if (studioWrapper.classList.contains('is-fullscreen')) {
          btnFullscreen.textContent = '⤓ Exit Fullscreen';
        } else {
          btnFullscreen.textContent = '⛶ Fullscreen';
        }
      });
    }

    // Notebook Title Rename
    const titleInput = document.getElementById('notebookTitleInput');
    if (titleInput) {
      titleInput.addEventListener('change', (e) => {
        if (activeNotebook) {
          activeNotebook.title = e.target.value.trim() || 'Untitled Notebook';
          saveNotebooks();
          renderBookshelf();
        }
      });
    }
  }

  // ==========================================
  // INITIALIZATION ON LOAD
  // ==========================================
  document.addEventListener('DOMContentLoaded', () => {
    initUI();
    initCanvas();
    loadTextBoxesState();
    initVoiceDictation();
    initReader();
    renderBookshelf();
    initNewNotebookModal();
    initCloudSyncManager();

    // Select initial notebook
    if (activeNotebook) {
      selectNotebook(activeNotebook.id);
    }
  });

})();
