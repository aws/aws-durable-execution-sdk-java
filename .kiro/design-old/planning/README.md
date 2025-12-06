# Planning Documents

This directory contains all incremental implementation plans and progress tracking.

## 📋 Start Here

1. **CONTEXT_GUIDE.md** - Complete context preservation guide (READ THIS FIRST for fresh context)
2. **TODO.md** - Current progress tracker (18/30 complete)
3. **INCREMENTAL_START.md** - Implementation philosophy and overview

## 📚 Incremental Plans

### Active Plans (Follow These)

1. **INCREMENTAL_PLAN.md** - Part 1: Foundation (Increments 1-7) ✅ Complete
2. **INCREMENTAL_PLAN_PART2.md** - Part 2: Serialization & Async (Increments 8-14) ✅ Complete
3. **INCREMENTAL_PLAN_REVISED.md** - Part 3: Lambda API Integration (Increments 17-22) 🔄 In Progress
4. **INCREMENTAL_PLAN_REVISED_PART4.md** - Part 4: Production Features (Increments 23-30) ⏳ Pending

### Notes

- Increments 15-16 were completed but will be refactored in 19-20
- Original Part 3 and Part 4 plans were superseded by revised versions
- See `../archive/` for historical documents

## 🎯 Current Status

**Completed:** Increments 1-18 (60% complete)  
**Next:** Increment 19 - Refactor DurableContext to use client  
**Remaining:** ~12 hours to MVP completion

## 📖 How to Use

1. Check TODO.md for current progress
2. Find the next increment in the appropriate plan file
3. Follow the step-by-step instructions
4. Run `mvn test` after each step
5. Commit after completing the increment
6. Update TODO.md

## 🔄 For Fresh Context

If starting from a fresh context (new session, new AI agent):

1. **Read CONTEXT_GUIDE.md first** - Contains everything needed to resume
2. Check TODO.md for current status
3. Review the next increment in the plan
4. Continue implementation

---

**Last Updated:** December 7, 2025  
**Status:** Active Development
