const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write any KelasKu collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("assignments").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("assignments").doc("a1").set({
      userId: ALICE_UID,
      title: "Hack",
      course: "CS",
      description: "Desc",
      dueDateIso: "2026-11-20",
      status: "pending",
      progress: 0,
      teacherName: "Dosen",
      attachments: [],
      createdAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});

test("UserProfile: owner create, role update, and cross-user / shadow-field denial", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alfath Student",
      email: "alfath@kelasku.id",
      role: "student",
      notificationsEnabled: true,
      seeded: true,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Role toggle update succeeds
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      role: "teacher",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject ghost field
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject cross-user read or write
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).update({
      displayName: "Hijack",
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});

test("Assignments: owner CRUD, status completion, teacher grading, and validation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = new Date(Date.now() - 5000);

  const assignRef = aliceDb.collection("users").doc(ALICE_UID).collection("assignments").doc("asg_1");
  await assertSucceeds(
    assignRef.set({
      userId: ALICE_UID,
      title: "Makalah Algoritma",
      course: "Algoritma & Struktur Data",
      description: "Analisis kompleksitas waktu.",
      dueDateIso: "2026-11-22",
      status: "pending",
      progress: 40,
      teacherName: "Dr. Budi Santoso",
      attachments: ["modul_3.pdf"],
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Partial update: attach scanned PDF & submit
  await assertSucceeds(
    assignRef.update({
      status: "submitted",
      progress: 100,
      attachments: ["modul_3.pdf", "scan_tulisan_tangan.pdf"],
      submissionNote: "Terlampir PDF hasil pindai kamera.",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject > 5 attachments
  await assertFails(
    assignRef.update({
      attachments: ["1.pdf", "2.pdf", "3.pdf", "4.pdf", "5.pdf", "6.pdf"],
      updatedAt: new Date(Date.now() - 800),
    })
  );

  // Partial update: mark completed
  await assertSucceeds(
    assignRef.update({
      status: "completed",
      progress: 100,
      submissionNote: "Sudah diunggah.",
      updatedAt: new Date(Date.now() - 600),
    })
  );

  // Teacher grade update
  await assertSucceeds(
    assignRef.update({
      status: "graded",
      gradeScore: 95,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // List subcollection succeeds for owner, fails for Bob
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("assignments").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("assignments").get());

  // Reject invalid status or @DocumentId `id` field
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("assignments").doc("asg_bad").set({
      id: "asg_bad",
      userId: ALICE_UID,
      title: "Bad",
      course: "CS",
      description: "Desc",
      dueDateIso: "2026-11-22",
      status: "pending",
      progress: 0,
      teacherName: "Dosen",
      attachments: [],
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(assignRef.delete());
});

test("Events, Materials, Conversations, and ChatMessages obey parent-gate and schema", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("events").doc("evt_1").set({
      userId: ALICE_UID,
      title: "Kuliah Tamu AI",
      dateIso: "2026-11-18",
      timeLabel: "09:00 WIB",
      colorTag: "orange",
      reminderMinutes: 30,
      category: "Kuliah",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("materials").doc("mat_1").set({
      userId: ALICE_UID,
      title: "Slide Basis Data",
      course: "Basis Data",
      type: "pdf",
      readProgress: 65,
      summary: "Normalisasi 1NF sampai BCNF.",
      downloaded: false,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("materials").doc("mat_1").update({
      downloaded: true,
      readProgress: 100,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  const convRef = aliceDb.collection("users").doc(ALICE_UID).collection("conversations").doc("conv_1");
  await assertSucceeds(
    convRef.set({
      userId: ALICE_UID,
      title: "Pengumuman Kelas XI-A",
      subtitle: "Wali Kelas",
      iconType: "announcement",
      unreadCount: 1,
      lastMessage: "Ujian dimulai Senin depan.",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Message under existing conversation succeeds
  await assertSucceeds(
    convRef.collection("messages").doc("msg_1").set({
      userId: ALICE_UID,
      sender: "me",
      senderName: "Saya",
      text: "Baik Pak, terima kasih infonya.",
      timeLabel: "09:15",
      createdAt: ts,
    })
  );

  // Message under non-existent parent conversation fails Parent Gate
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("conversations")
      .doc("missing_conv")
      .collection("messages")
      .doc("msg_orphan")
      .set({
        userId: ALICE_UID,
        sender: "me",
        senderName: "Saya",
        text: "Orphan",
        timeLabel: "09:15",
        createdAt: ts,
      })
  );
});
