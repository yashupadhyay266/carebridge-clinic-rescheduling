import React, { useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import "./style.css";

const API = "http://localhost:8080/api";
const newKey = (operation) => `${operation.toLowerCase()}-${crypto.randomUUID()}`;
  const displayStatus = (status) => ({
  OPEN: "Available",
  BOOKED: "Booked",
  CANCELLED: "Cancelled",
  CONFIRMED: "Confirmed",
  RESCHEDULE_PENDING: "Needs rescheduling",
  RESCHEDULED: "Rescheduled",
  PENDING: "Waiting for review",
  ACCEPTED: "Accepted",
  REJECTED: "Declined",
}[status] || status);

function App() {
  const [data, setData] = useState({ providers: [], slots: [], appointments: [], suggestions: [] });
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState(null);
  const [selectedProvider, setSelectedProvider] = useState("");
  const [patientName, setPatientName] = useState("Test Patient");
  const [appointmentType, setAppointmentType] = useState("ROUTINE");
  const lastBookingKey = useRef(null);
  const lastCancellationKey = useRef(null);
  const [selectedSlot, setSelectedSlot] = useState("");

  const load = async () => {
    try {
      const response = await fetch(`${API}/dashboard`);
      const body = await response.json();
      if (!response.ok) throw new Error(body.error || "Dashboard request failed");
      setData({ providers: [], slots: [], appointments: [], suggestions: [], ...body });
      setSelectedProvider((current) => current && body.providers?.some((p) => p.id === current && p.status !== "OFFLINE") ? current : body.providers?.find((p) => p.status !== "OFFLINE")?.id || "");
      setSelectedSlot((current) => current || body.slots?.find((slot) => slot.status === "OPEN")?.id || "");
    } catch (error) { setMessage({ type: "error", text: error.message }); }
  };
  useEffect(() => { load(); }, []);

  const request = async (url, options = {}, successText) => {
    setLoading(true); setMessage(null);
    try {
      const response = await fetch(API + url, options);
      const text = await response.text();
      const body = text ? JSON.parse(text) : null;
      if (!response.ok) throw new Error(body?.error || `Request failed (${response.status})`);
      setMessage({ type: "success", text: successText || `Success (${response.status})` });
      await load(); return body;
    } catch (error) { setMessage({ type: "error", text: error.message }); return null; }
    finally { setLoading(false); }
  };
  const action = (url, successText) => request(url, { method: "POST" }, successText);
  const markUnavailable = async (providerId, providerName) => {
    await action(`/providers/${providerId}/offline`, `${providerName} is unavailable. Checking affected appointments for new options.`);
    window.setTimeout(load, 1200);
    window.setTimeout(load, 3000);
  };
  const book = (slotId = selectedSlot) => {
    if (!selectedProvider || selectedProviderRecord?.status === "OFFLINE" || !slotId || !patientName.trim()) { setMessage({ type: "error", text: "Choose an available doctor and an available appointment time." }); return; }
    if (selectedSlotRecord?.status !== "OPEN") {
      setMessage({ type: "error", text: `This appointment time is already ${displayStatus(selectedSlotRecord?.status || "unavailable").toLowerCase()}. Please choose an available time.` });
      return;
    }
    const key = newKey("BOOK");
    lastBookingKey.current = key;
    return request(`/slots/${slotId}/book`, { method: "POST", headers: { "Content-Type": "application/json", "X-Provider-Id": selectedProvider, "Idempotency-Key": key }, body: JSON.stringify({ patientName: patientName.trim(), appointmentType }) }, "Appointment booked successfully.");
  };
  const cancel = () => {
    if (!selectedProvider || !selectedSlot) { setMessage({ type: "error", text: "Select a provider and slot to cancel." }); return; }
    const key = newKey("CANCEL");
    lastCancellationKey.current = key;
    return request(`/slots/${selectedSlot}/cancel`, { method: "POST", headers: { "X-Provider-Id": selectedProvider, "Idempotency-Key": key } }, "Appointment time cancelled successfully.");
  };
  const provider = (id) => data.providers.find((item) => item.id === id);
  const selectedProviderRecord = provider(selectedProvider);
  const providerSlots = data.slots.filter((slot) => slot.providerId === selectedProvider);
  const providersWithAppointments = data.providers.filter((p) => p.status !== "OFFLINE" && data.appointments.some((a) => a.providerId === p.id && a.status === "CONFIRMED"));
  const selectedSlotRecord = data.slots.find((slot) => slot.id === selectedSlot);
  const openSlots = useMemo(() => data.slots.filter((slot) => slot.status === "OPEN"), [data.slots]);
  const pending = data.suggestions.filter((item) => item.status === "PENDING").length;

  return <main>
    <header><div><p className="eyebrow">CAREBRIDGE · FRONT DESK</p><h1>AI Re-Scheduling Engine</h1><p className="sub">Resolve disruptions before patients call the clinic.</p></div><div className="pulse"><i /> Engine active <button className="refresh" onClick={load}>Refresh</button></div></header>
    {message && <div className={`notice ${message.type}`} role="alert">{message.text}<button onClick={() => setMessage(null)}>×</button></div>}
    <section className="stats"><div><b>{pending}</b><span>Awaiting approval</span></div><div><b>{data.appointments.filter((x) => x.status === "RESCHEDULED").length}</b><span>Resolved today</span></div><div><b>{data.providers.filter((x) => x.status === "OFFLINE").length}</b><span>Providers offline</span></div><div><b>{openSlots.length}</b><span>Open slots</span></div></section>
    <section className="demo"><div><strong>Provider availability</strong><span>Mark a doctor unavailable to find new appointment times for affected patients.</span></div><div className="actions">{providersWithAppointments.map((p) => <button disabled={loading} onClick={() => markUnavailable(p.id, p.name)} key={p.id}>Mark {p.name.replace("Dr. ", "")} unavailable</button>)}</div></section>
    <section className="operations"><div className="section-heading"><div><h2>Appointment scheduling</h2><p>Choose a doctor and time, then book or cancel an appointment slot.</p></div></div><div className="form-grid">
      <label>Doctor<select value={selectedProvider} onChange={(e) => { setSelectedProvider(e.target.value); const first = data.slots.find((slot) => slot.providerId === e.target.value); setSelectedSlot(first?.id || ""); }}>{data.providers.filter((p) => p.status !== "OFFLINE").map((p) => <option value={p.id} key={p.id}>{p.name} (Available)</option>)}</select></label>
      <label>Appointment time<select value={selectedSlot} onChange={(e) => setSelectedSlot(e.target.value)}>{providerSlots.length === 0 && <option value="">No appointment times for this doctor</option>}{providerSlots.map((slot) => <option value={slot.id} key={slot.id}>{new Date(slot.startTime).toLocaleString()} · {displayStatus(slot.status)}</option>)}</select></label>
      <label>Patient name<input value={patientName} onChange={(e) => setPatientName(e.target.value)} /></label><label>Appointment type<select value={appointmentType} onChange={(e) => setAppointmentType(e.target.value)}><option>ROUTINE</option><option>FOLLOW_UP</option><option>URGENT</option></select></label>
    </div><div className="operation-row"><div><b className="operation-label">Book an appointment</b><span className="operation-help">The application protects repeated requests automatically.</span></div><button disabled={loading} onClick={() => book()}>Book appointment</button></div><div className="operation-row"><div><b className="operation-label">Cancel an available time</b><span className="operation-help">Only the doctor who owns the time can cancel it.</span></div><button className="danger" disabled={loading} onClick={cancel}>Cancel time</button></div>{selectedSlotRecord && <div className="selected-detail">Selected: <b>{new Date(selectedSlotRecord.startTime).toLocaleString()}</b> · with <b>{provider(selectedSlotRecord.providerId)?.name || selectedSlotRecord.providerId}</b> · <b>{displayStatus(selectedSlotRecord.status)}</b></div>}</section>
    <section className="list-section"><div className="section-heading"><div><h2>Patient appointments</h2><p>All scheduled appointments and their current status.</p></div></div><div className="table-wrap"><table><thead><tr><th>Patient</th><th>Doctor</th><th>Appointment time</th><th>Type</th><th>Status</th></tr></thead><tbody>{data.appointments.map((a) => <tr key={a.id}><td>{a.patientName}</td><td>{provider(a.providerId)?.name || a.providerId}</td><td>{a.slotId}</td><td>{a.appointmentType}</td><td><span className="table-status">{displayStatus(a.status)}</span></td></tr>)}</tbody></table></div></section>
    <section className="list-section"><div className="section-heading"><div><h2>Available appointment times</h2><p>Choose an available time in the scheduling section above.</p></div></div><div className="table-wrap"><table><thead><tr><th>Time</th><th>Doctor</th><th>Start</th><th>Duration</th><th>Status</th></tr></thead><tbody>{data.slots.map((slot) => <tr key={slot.id}><td>{slot.id}</td><td>{provider(slot.providerId)?.name || slot.providerId}</td><td>{new Date(slot.startTime).toLocaleString()}</td><td>{slot.durationMinutes} min</td><td><span className={`table-status ${slot.status.toLowerCase()}`}>{displayStatus(slot.status)}</span></td></tr>)}</tbody></table></div></section>
    <h2>Rescheduling review <small>{pending} waiting for review</small></h2><div className="queue">{data.suggestions.length === 0 && <div className="empty">No appointment changes need review right now.</div>}{data.suggestions.map((s) => <article className={`card ${s.status.toLowerCase()}`} key={s.id}><div className="cardtop"><span className="patient">{s.patientName}</span><span className="badge">{s.triggerType === "PROVIDER_OFFLINE" ? "DOCTOR UNAVAILABLE" : "TIME CANCELLED"}</span><span className={`status ${s.status.toLowerCase()}`}>{displayStatus(s.status)}</span></div><div className="route"><div><label>Current appointment</label><strong>{s.currentProvider}</strong><span>{s.currentSlot}</span></div><div className="arrow">→</div><div className="recommended"><label>Suggested new appointment</label><strong>{s.recommendedProvider}</strong><span>{s.recommendedStart && new Date(s.recommendedStart).toLocaleString()}</span></div><div className="confidence"><b>{Math.round(s.confidence * 100)}%</b><span>verified match</span></div></div><p className="reason">✦ <b>{s.recommendationSource === "LLM" ? "AI explanation:" : "Scheduling explanation:"}</b> {s.explanation}</p>{s.recommendationSource === "LLM" && <span className="source-badge">AI-assisted explanation</span>}{s.status === "PENDING" && <div className="cardactions"><button className="approve" disabled={loading} onClick={() => action(`/suggestions/${s.id}/accept`, "Appointment changed successfully.")}>Accept change</button><button className="reject" disabled={loading} onClick={() => action(`/suggestions/${s.id}/reject`, "Suggestion declined.")}>Decline</button></div>}</article>)}</div>
  </main>;
}
createRoot(document.getElementById("root")).render(<App />);