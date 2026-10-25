/** Local persistence. A successful operation means its complete transaction committed. */
export class LocalSessionRepository {
  constructor(database) {
    this.database = database;
  }

  static open(onVersionChange = () => {}) {
    return new Promise((resolve, reject) => {
      if (!globalThis.indexedDB) return reject(new Error("Local recording storage is unavailable."));
      const request = indexedDB.open("biosignal-bridge", 1);
      request.onupgradeneeded = () => {
        request.result.createObjectStore("sessions", { keyPath: "id" });
      };
      let blocked = false;
      request.onblocked = () => {
        blocked = true;
        reject(new Error("Close the other workspace tab, then reopen this one to save recordings."));
      };
      request.onerror = () => reject(request.error || new Error("The recording database could not open."));
      request.onsuccess = () => {
        const database = request.result;
        if (blocked) { database.close(); return; }
        database.onversionchange = () => { database.close(); onVersionChange(); };
        resolve(new LocalSessionRepository(database));
      };
    });
  }

  transaction(mode, operation) {
    return new Promise((resolve, reject) => {
      const transaction = this.database.transaction("sessions", mode);
      const request = operation(transaction.objectStore("sessions"));
      transaction.oncomplete = () => resolve(request?.result);
      transaction.onerror = () => reject(transaction.error || new Error("The recording could not be saved."));
      transaction.onabort = () => reject(transaction.error || new Error("The recording operation was cancelled."));
    });
  }

  async list() {
    const sessions = await this.transaction("readonly", store => store.getAll());
    return sessions.sort((a, b) => b.startedAtEpochMs - a.startedAtEpochMs);
  }

  save(session) { return this.transaction("readwrite", store => store.put(session)); }
  remove(id) { return this.transaction("readwrite", store => store.delete(id)); }
  close() { this.database.close(); }
}
