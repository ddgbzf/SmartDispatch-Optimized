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
        // 将 insertOrder >= beforePerson.insertOrder 的记录+1，腾出位置
        personDao.shiftInsertOrder(beforePerson.insertOrder)
        personDao.insert(Person(name = name, employeeId = employeeId, jobType = jobType, insertOrder = beforePerson.insertOrder))
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
        // 确定新工序的sortOrder
        var newSortOrder = 0
        if (beforeProcess != null) {
            val targetOrder = skillScoreDao.getMinSortOrder(beforeProcess)
            if (targetOrder != null) {
                // 将 >= targetOrder 的记录全部+1，腾出位置
                skillScoreDao.shiftSortOrder(targetOrder)
                newSortOrder = targetOrder
            } else {
                // 目标工序不存在，放到最后
                val maxOrder = skillScoreDao.getProcessOrders().maxOfOrNull { it.sortOrder } ?: -1
                newSortOrder = maxOrder + 1
            }
        } else {
            // 没有指定位置，放到最后
            val maxOrder = skillScoreDao.getProcessOrders().maxOfOrNull { it.sortOrder } ?: -1
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
