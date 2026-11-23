import { useCallback, useLayoutEffect, useRef } from "react";

function fillWhite(canvas) {
  const context = canvas.getContext("2d");
  context.save();
  context.resetTransform();
  context.fillStyle = "#ffffff";
  context.fillRect(0, 0, canvas.width, canvas.height);
  context.restore();
}

export default function SignatureBox({ label, value, onChange, simulacao = false }) {
  const canvasRef = useRef(null);
  const drawingRef = useRef(false);
  const sizeRef = useRef(null);
  const publishedRef = useRef(null);
  const hydrationVersion = useRef(0);

  const resize = useCallback(() => {
    // Atribuir width ou height também apaga o caminho do traço em andamento.
    if (drawingRef.current) return;
    const canvas = canvasRef.current;
    const { width, height } = canvas.getBoundingClientRect();
    const ratio = window.devicePixelRatio || 1;
    const pixelWidth = Math.floor(width * ratio);
    const pixelHeight = Math.floor(height * ratio);
    if (!pixelWidth || !pixelHeight) return;

    const previous = sizeRef.current;
    sizeRef.current = { width, height, ratio };
    if (previous?.ratio === ratio && canvas.width === pixelWidth && canvas.height === pixelHeight) return;

    let snapshot;
    if (previous) {
      snapshot = document.createElement("canvas");
      snapshot.width = canvas.width;
      snapshot.height = canvas.height;
      snapshot.getContext("2d").drawImage(canvas, 0, 0);
    }
    canvas.width = pixelWidth;
    canvas.height = pixelHeight;
    const context = canvas.getContext("2d");
    fillWhite(canvas);
    if (snapshot) context.drawImage(snapshot, 0, 0, pixelWidth, pixelHeight);
    context.scale(ratio, ratio);
    context.lineWidth = 2;
    context.lineCap = "round";
    context.lineJoin = "round";
    context.strokeStyle = "#0f172a";
  }, []);

  useLayoutEffect(() => {
    resize();
    const observer = new ResizeObserver(resize);
    observer.observe(canvasRef.current);

    let resolution;
    function watchResolution() {
      resolution?.removeEventListener("change", watchResolution);
      resolution = window.matchMedia(`(resolution: ${window.devicePixelRatio}dppx)`);
      resolution.addEventListener("change", watchResolution);
      resize();
    }
    watchResolution();
    return () => {
      observer.disconnect();
      resolution.removeEventListener("change", watchResolution);
      hydrationVersion.current++;
    };
  }, [resize]);

  useLayoutEffect(() => {
    const version = ++hydrationVersion.current;
    const fromCanvas = publishedRef.current !== null && value === publishedRef.current;
    publishedRef.current = null;
    if (fromCanvas) return;

    const canvas = canvasRef.current;
    drawingRef.current = false;
    resize();
    fillWhite(canvas);
    if (value?.startsWith("data:image")) {
      const image = new Image();
      image.onload = () => {
        if (version !== hydrationVersion.current) return;
        const context = canvas.getContext("2d");
        context.save();
        context.resetTransform();
        context.drawImage(image, 0, 0, canvas.width, canvas.height);
        context.restore();
      };
      image.src = value;
    }
  }, [value, resize]);

  const getPoint = (event) => {
    const rect = canvasRef.current.getBoundingClientRect();
    const size = sizeRef.current;
    const sourceEvent = event.touches?.[0] || event;
    return {
      x: (sourceEvent.clientX - rect.left) * (size.width / rect.width),
      y: (sourceEvent.clientY - rect.top) * (size.height / rect.height),
    };
  };

  const startDrawing = (event) => {
    event.preventDefault();
    resize();
    if (!sizeRef.current) return;
    // Uma edição local tem precedência sobre a hidratação ainda pendente.
    hydrationVersion.current++;
    const context = canvasRef.current.getContext("2d");
    const point = getPoint(event);
    drawingRef.current = true;
    context.beginPath();
    context.moveTo(point.x, point.y);
  };

  const draw = (event) => {
    if (!drawingRef.current) return;
    event.preventDefault();
    const context = canvasRef.current.getContext("2d");
    const point = getPoint(event);
    context.lineTo(point.x, point.y);
    context.stroke();
  };

  const stopDrawing = () => {
    if (!drawingRef.current) return;
    drawingRef.current = false;
    publishedRef.current = canvasRef.current.toDataURL("image/png");
    onChange(publishedRef.current);
    resize();
  };

  const clear = () => {
    hydrationVersion.current++;
    drawingRef.current = false;
    resize();
    fillWhite(canvasRef.current);
    publishedRef.current = "";
    onChange("");
  };

  return (
    <div className="space-y-2">
      {simulacao && <p className="text-xs font-bold text-red-700">Desenhe TESTE; não use assinatura real.</p>}
      <div className="flex items-center justify-between gap-2">
        <label className="block text-sm font-semibold text-slate-700">
          {simulacao ? label.replace("desenhada", "fictícia") + " — TESTE" : label}
        </label>
        <button
          type="button"
          onClick={clear}
          className="rounded border border-slate-200 px-2 py-1 text-xs font-bold text-slate-500 hover:bg-slate-50"
        >
          Limpar
        </button>
      </div>
      <canvas
        ref={canvasRef}
        className="h-32 w-full rounded-lg border border-slate-300 bg-white touch-none"
        onMouseDown={startDrawing}
        onMouseMove={draw}
        onMouseUp={stopDrawing}
        onMouseLeave={stopDrawing}
        onTouchStart={startDrawing}
        onTouchMove={draw}
        onTouchEnd={stopDrawing}
      />
      {!value && (
        <p className="text-xs text-slate-400">
          Assine no campo acima com mouse ou toque.
        </p>
      )}
    </div>
  );
}
