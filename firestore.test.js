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
const ADMIN_UID = "admin_789";

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

test("Unauthenticated user: cannot read or write user profiles or progress", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("lesson_progress").doc("lesson_1").get()
  );
});

test("Authenticated user: can create and update own lesson progress, but cannot regress starsEarned or overwrite with older epoch", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  const progressRef = aliceDb.collection("users").doc(ALICE_UID).collection("lesson_progress").doc("lesson_1");

  await assertSucceeds(
    progressRef.set({
      userId: ALICE_UID,
      levelId: 1,
      starsEarned: 3,
      bestScore: 300,
      isCompleted: true,
      mistakeCategory: "NONE",
      clientUpdatedAtEpoch: 2000,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Attempting to overwrite with lower starsEarned (2 < 3) or older epoch (1000 < 2000) MUST FAIL
  await assertFails(
    progressRef.update({
      starsEarned: 2,
      clientUpdatedAtEpoch: 2500,
      updatedAt: now,
    })
  );

  await assertFails(
    progressRef.update({
      starsEarned: 3,
      clientUpdatedAtEpoch: 1000,
      updatedAt: now,
    })
  );

  // Updating with higher or equal stars and newer epoch succeeds
  await assertSucceeds(
    progressRef.update({
      starsEarned: 3,
      bestScore: 350,
      clientUpdatedAtEpoch: 3000,
      updatedAt: now,
    })
  );
});

test("Cross-user isolation: Bob cannot read or write Alice's lesson progress", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const now = new Date();
    await context.firestore().collection("users").doc(ALICE_UID).collection("lesson_progress").doc("lesson_1").set({
      userId: ALICE_UID,
      levelId: 1,
      starsEarned: 2,
      bestScore: 200,
      isCompleted: true,
      mistakeCategory: "NONE",
      clientUpdatedAtEpoch: 1000,
      createdAt: now,
      updatedAt: now,
    });
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("lesson_progress").doc("lesson_1").get()
  );
});

test("Admin security: Regular user cannot create curriculum lessons or grant themselves admin role", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();

  // Cannot self-assign admin role from client
  await assertFails(
    aliceDb.collection("admins").doc(ALICE_UID).set({ role: "admin" })
  );

  // Cannot create curriculum lesson without /admins/{uid} document
  await assertFails(
    aliceDb.collection("curriculum_lessons").doc("lesson_custom_1").set({
      stageNumber: 1,
      levelOrder: 1,
      titleBn: "নতুন পাঠ",
      arabicContent: "بَ",
      pronunciationBn: "বা যবর বা",
      meaningBn: "হরকত অনুশীলন",
      audioSourceUrl: "verified_ar_ba",
      teacherVerified: true,
      isPublished: true,
      authorUid: ALICE_UID,
      createdAt: now,
      updatedAt: now,
    })
  );
});

test("Verified Admin: can create verified lesson, but cannot publish unverified lesson", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection("admins").doc(ADMIN_UID).set({ role: "super_admin" });
  });

  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const now = new Date();

  // Publishing while teacherVerified == false MUST FAIL
  await assertFails(
    adminDb.collection("curriculum_lessons").doc("lesson_unverified").set({
      stageNumber: 5,
      levelOrder: 1,
      titleBn: "সূরা আন-নাবা অনুশীলন",
      arabicContent: "عَمَّ يَتَسَاءَلُونَ",
      pronunciationBn: "আম্মা ইয়াতাসাআলূন",
      meaningBn: "তারা কী বিষয়ে জিজ্ঞাসাবাদ করছে?",
      audioSourceUrl: "verified_naba_1",
      teacherVerified: false,
      isPublished: true,
      authorUid: ADMIN_UID,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Publishing with teacherVerified == true SUCCEEDS
  await assertSucceeds(
    adminDb.collection("curriculum_lessons").doc("lesson_verified").set({
      stageNumber: 5,
      levelOrder: 1,
      titleBn: "সূরা আন-নাবা অনুশীলন",
      arabicContent: "عَمَّ يَتَسَاءَلُونَ",
      pronunciationBn: "আম্মা ইয়াতাসাআলূন",
      meaningBn: "তারা কী বিষয়ে জিজ্ঞাসাবাদ করছে?",
      audioSourceUrl: "verified_naba_1",
      teacherVerified: true,
      isPublished: true,
      authorUid: ADMIN_UID,
      createdAt: now,
      updatedAt: now,
    })
  );
});
