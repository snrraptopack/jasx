// Browser code for Browser.jasx (targets/web.md §7.1). Plain ES module functions: each is called as
// fn(event, payload), and may return a cleanup.

export function showTab(event, { show, hide }) {
  show.hidden = false;
  for (const section of hide) section.hidden = true;
}

export async function copy(event, { text }) {
  await navigator.clipboard.writeText(text);
  event.currentTarget.textContent = "Copied";
}

// `action` is a Java Action: calling it runs it on the server, and resolves once the page has updated.
export async function confirmThen(event, { question, action }) {
  if (window.confirm(question)) await action();
}

export function count(event, { counter, max }) {
  const length = event.target.value.length;
  counter.textContent = length + " / " + max;
  counter.classList.toggle("too-long", length > max);
}

// Called when the element appears, and again whenever the server changes `values`. The cleanup runs before
// the next drawing, and when the element leaves the page.
export function drawBars(event, { bars, values }) {
  for (const value of values) {
    const bar = document.createElement("div");
    bar.style.height = value * 8 + "px";
    bars.appendChild(bar);
  }
  return () => bars.replaceChildren();
}
