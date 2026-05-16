package com.example.smartdispatch.data

import com.example.smartdispatch.data.dao.*
import com.example.smartdispatch.data.entity.*
import kotlinx.coroutines.flow.Flow

class DispatchRepository(
    private val personDao: PersonDao,
    private val skillScoreDao: SkillScoreDao,
    private val productDao: ProductDao,
    private val productProcessDao: ProductProcessDao,
    private val assignmentDao: AssignmentDao,
    private val fixedCellDao: FixedCellDao
) {
    val allPersons: Flow<List<Person>> = personDao.getAll()
    val leavePersons: Flow<List<Person>> = personDao.getOnLeave()
    val availablePersons: Flow<List<Person>> = personDao.getAvailable()
    val allProcessNames: Flow<List<String>> = skillScoreDao.getAllProcessNames()
    val allProducts: Flow<List<Product>> = productDao.getAll()
    val allAssignments: Flow<List<Assignment>> = assignmentDao.getAll()
    val allFixedCells: Flow<List<FixedCell>> = fixedCellDao.getAll()

    suspend fun addPerson(name: String, employeeId: String = "", jobType: String = "") = personDao.insert(Person(name = name, employeeId = employeeId, jobType = jobType, insertOrder = System.currentTimeMillis().toInt()))
    suspend fun updatePerson(person: Person) = personDao.update(person)
    suspend fun deletePerson(person: Person) = personDao.delete(person)

    suspend fun insertPersonBefore(beforePerson: Person, name: String, employeeId: String = "", jobType: String = "") {
        // 找到beforePerson前面的人，计算中间insertOrder
        val allPersons = personDao.getAllOnce()
        val beforeIndex = allPersons.indexOfFirst { it.id == beforePerson.id }
        val prevInsertOrder = if (beforeIndex > 0) allPersons[beforeIndex - 1].insertOrder else 0
        val newInsertOrder = (prevInsertOrder + beforePerson.insertOrder) / 2
        
        // 如果中间没有空间（差值<=1），则需要重新分配所有insertOrder
        if (newInsertOrder <= prevInsertOrder || newInsertOrder >= beforePerson.insertOrder) {
            // 重新分配：按当前顺序，间隔100
            allPersons.forEachIndexed { index, p ->
                personDao.update(p.copy(insertOrder = (index + 1) * 100))
            }
            // 重新计算新位置
            val newBeforeIndex = allPersons.indexOfFirst { it.id == beforePerson.id }
            val newPrevOrder = if (newBeforeIndex > 0) allPersons[newBeforeIndex - 1].insertOrder else 0
            val newBeforeOrder = allPersons[newBeforeIndex].insertOrder
            val finalInsertOrder = (newPrevOrder + newBeforeOrder) / 2
            personDao.insert(Person(name = name, employeeId = employeeId, jobType = jobType, insertOrder = finalInsertOrder))
        } else {
            personDao.insert(Person(name = name, employeeId = employeeId, jobType = jobType, insertOrder = newInsertOrder))
        }
    }

    suspend fun setSkillScore(personId: Int, processName: String, score: Int) {
        val existing = skillScoreDao.find(personId, processName)
        if (existing != null) {
            skillScoreDao.update(existing.copy(score = score))
        } else {
            skillScoreDao.insert(SkillScore(personId = personId, processName = processName, score = score))
        }
    }

    suspend fun addProduct(name: String, capacity: Int, requiredPeople: Int): Long {
        return productDao.insert(Product(name = name, capacity = capacity, requiredPeople = requiredPeople))
    }
    suspend fun updateProduct(product: Product) = productDao.update(product)
    suspend fun deleteProduct(product: Product) = productDao.delete(product)

    suspend fun addProcess(productId: Int, processName: String, sortOrder: Int) {
        productProcessDao.insert(ProductProcess(productId = productId, processName = processName, sortOrder = sortOrder))
    }
    suspend fun updateProcess(process: ProductProcess) = productProcessDao.update(process)
    suspend fun deleteProcess(process: ProductProcess) = productProcessDao.delete(process)
    suspend fun deleteProcessesByProduct(productId: Int) = productProcessDao.deleteByProduct(productId)
    suspend fun getProcesses(productId: Int): Flow<List<ProductProcess>> = productProcessDao.getByProduct(productId)

    suspend fun getPersonById(id: Int): Person? = personDao.findById(id)

    suspend fun getScoresByPerson(personId: Int): List<SkillScore> = skillScoreDao.getByPersonOnce(personId)

    suspend fun getProcessesOnce(productId: Int): List<ProductProcess> = productProcessDao.getByProductOnce(productId)

    suspend fun updateAssignment(assignment: Assignment) = assignmentDao.update(assignment)
    suspend fun deleteDynamicAssignments() = assignmentDao.deleteDynamic()
    suspend fun clearAllAssignments() = assignmentDao.deleteAll()
    suspend fun getFixedAssignments(): List<Assignment> = assignmentDao.getFixedOnce()
    suspend fun insertAssignments(assignments: List<Assignment>) = assignmentDao.insertAll(assignments)

    // 工序评分管理
    suspend fun deleteProcess(processName: String) = skillScoreDao.deleteByProcessName(processName)
    suspend fun renameProcess(oldName: String, newName: String) = skillScoreDao.renameProcess(oldName, newName)
    suspend fun processNameExists(processName: String) = skillScoreDao.processNameExists(processName) > 0
    suspend fun getAllProcessNamesOnce() = skillScoreDao.getAllProcessNamesOnce()
    suspend fun addProcessForAllPersons(processName: String, persons: List<Person>, beforeProcess: String? = null) {
        // 获取所有工序及其sortOrder（按当前顺序）
        val orders = skillScoreDao.getProcessOrders()

        // 确定新工序的sortOrder（使用中间值算法，与insertPersonBefore一致）
        var newSortOrder = 0
        if (beforeProcess != null && beforeProcess.isNotBlank()) {
            val targetIndex = orders.indexOfFirst { it.processName == beforeProcess }
            if (targetIndex >= 0) {
                val targetOrder = orders[targetIndex].sortOrder
                val prevOrder = if (targetIndex > 0) orders[targetIndex - 1].sortOrder else 0
                val midOrder = (prevOrder + targetOrder) / 2

                if (midOrder > prevOrder && midOrder < targetOrder) {
                    // 中间有空间，直接使用中间值
                    newSortOrder = midOrder
                } else {
                    // 中间没有空间，重新分配所有工序的sortOrder（间隔100）
                    orders.forEachIndexed { index, po ->
                        skillScoreDao.updateProcessSortOrder(po.processName, (index + 1) * 100)
                    }
                    // 重新获取并计算中间值
                    val newOrders = skillScoreDao.getProcessOrders()
                    val newTargetIndex = newOrders.indexOfFirst { it.processName == beforeProcess }
                    val newTargetOrder = newOrders[newTargetIndex].sortOrder
                    val newPrevOrder = if (newTargetIndex > 0) newOrders[newTargetIndex - 1].sortOrder else 0
                    newSortOrder = (newPrevOrder + newTargetOrder) / 2
                }
            } else {
                // 目标工序不存在，放到最后
                val maxOrder = orders.maxOfOrNull { it.sortOrder } ?: -1
                newSortOrder = maxOrder + 1
            }
        } else {
            // 没有指定位置，放到最后
            val maxOrder = orders.maxOfOrNull { it.sortOrder } ?: -1
            newSortOrder = maxOrder + 1
        }
        for (person in persons) {
            skillScoreDao.insert(SkillScore(personId = person.id, processName = processName, score = 0, sortOrder = newSortOrder))
        }
    }

    // 固定单元格
    suspend fun saveFixedCells(colIndex: Int, cells: List<FixedCell>) {
        fixedCellDao.deleteByColumn(colIndex)
        fixedCellDao.insertAll(cells)
    }
    suspend fun deleteFixedCellsByColumn(colIndex: Int) = fixedCellDao.deleteByColumn(colIndex)
    suspend fun clearAllFixedCells() = fixedCellDao.deleteAll()

    suspend fun clearAll() {
        assignmentDao.deleteAll()
        fixedCellDao.deleteAll()
        productProcessDao.deleteAll()
        skillScoreDao.deleteAll()
        productDao.deleteAll()
        personDao.deleteAll()
    }
}
