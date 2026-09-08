import asyncio
import unittest

from any2api_automation.session_pool import AccountSessionPool


class AccountSessionPoolTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.closed = []

        async def dispose(value):
            self.closed.append(value)

        self.pool = AccountSessionPool(2, dispose)

    async def asyncTearDown(self):
        await self.pool.close()

    async def test_switching_accounts_reuses_their_own_session(self):
        async with self.pool.borrow("a") as slot:
            slot.value = object()
            first = slot.value
        async with self.pool.borrow("b") as slot:
            slot.value = object()
        async with self.pool.borrow("a") as slot:
            self.assertIs(slot.value, first)
        self.assertEqual(self.closed, [])

    async def test_only_least_recently_used_idle_session_is_evicted(self):
        for key in ("a", "b", "a", "c"):
            async with self.pool.borrow(key) as slot:
                slot.value = key
        self.assertEqual(self.closed, ["b"])

    async def test_same_account_waits_but_other_account_can_run(self):
        entered = asyncio.Event()

        async def second():
            async with self.pool.borrow("a"):
                entered.set()

        async with self.pool.borrow("a"):
            waiting = asyncio.create_task(second())
            await asyncio.sleep(0)
            async with self.pool.borrow("b"):
                self.assertFalse(entered.is_set())
        await asyncio.wait_for(waiting, 1)
        self.assertTrue(entered.is_set())

    async def test_busy_sessions_are_not_evicted_at_capacity(self):
        entered = asyncio.Event()

        async def third():
            async with self.pool.borrow("c"):
                entered.set()

        async with self.pool.borrow("a") as a:
            a.value = "a"
            async with self.pool.borrow("b") as b:
                b.value = "b"
                waiting = asyncio.create_task(third())
                await asyncio.sleep(0)
                self.assertFalse(entered.is_set())
                self.assertEqual(self.closed, [])
            await asyncio.wait_for(waiting, 1)
            self.assertEqual(self.closed, ["b"])

    async def test_cancelled_waiter_does_not_take_account_ownership(self):
        async def waiter():
            async with self.pool.borrow("a"):
                self.fail("cancelled waiter acquired the account")

        async with self.pool.borrow("a"):
            task = asyncio.create_task(waiter())
            await asyncio.sleep(0)
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task
        async with self.pool.borrow("a") as slot:
            slot.value = "restored"

    async def test_failed_creation_releases_reserved_capacity(self):
        with self.assertRaisesRegex(ValueError, "startup"):
            async with self.pool.borrow("a"):
                raise ValueError("startup")
        async with self.pool.borrow("a") as slot:
            self.assertIsNone(slot.value)
            slot.value = "recovered"

    async def test_close_waits_for_active_operation_and_rejects_new_work(self):
        async with self.pool.borrow("a") as slot:
            slot.value = "a"
            closing = asyncio.create_task(self.pool.close())
            await asyncio.sleep(0)
            self.assertFalse(closing.done())
            with self.assertRaisesRegex(RuntimeError, "closed"):
                async with self.pool.borrow("b"):
                    self.fail("pool accepted work during shutdown")
        await asyncio.wait_for(closing, 1)
        self.assertEqual(self.closed, ["a"])
        await self.pool.close()
        self.assertEqual(self.closed, ["a"])

    async def test_cleanup_failure_does_not_skip_other_sessions(self):
        closed = []

        async def dispose(value):
            closed.append(value)
            if value == "a":
                raise RuntimeError("close failed")

        pool = AccountSessionPool(2, dispose)
        for key in ("a", "b"):
            async with pool.borrow(key) as slot:
                slot.value = key
        with self.assertRaises(ExceptionGroup):
            await pool.close()
        self.assertEqual(closed, ["a", "b"])

    async def test_failed_eviction_releases_the_reserved_slot(self):
        async def dispose(value):
            raise RuntimeError("dispose failed")

        pool = AccountSessionPool(1, dispose)
        async with pool.borrow("a") as slot:
            slot.value = "a"
        with self.assertRaisesRegex(RuntimeError, "dispose failed"):
            async with pool.borrow("b"):
                self.fail("operation must not start after cleanup failure")
        async with pool.borrow("c") as slot:
            self.assertIsNone(slot.value)
        await pool.close()

    async def test_cancelled_eviction_waits_for_cleanup_before_releasing_capacity(self):
        started = asyncio.Event()
        finish = asyncio.Event()

        async def dispose(value):
            started.set()
            await finish.wait()

        pool = AccountSessionPool(1, dispose)
        async with pool.borrow("a") as slot:
            slot.value = "a"

        async def replace():
            async with pool.borrow("b"):
                self.fail("cancelled operation started")

        task = asyncio.create_task(replace())
        await asyncio.wait_for(started.wait(), 1)
        task.cancel()
        await asyncio.sleep(0)
        self.assertFalse(task.done())
        finish.set()
        with self.assertRaises(asyncio.CancelledError):
            await task
        async with pool.borrow("c") as slot:
            self.assertIsNone(slot.value)
        await pool.close()


if __name__ == "__main__":
    unittest.main()
