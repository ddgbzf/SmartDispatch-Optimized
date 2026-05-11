package com.example.smartdispatch.data

import com.example.smartdispatch.data.dao.*
import com.example.smartdispatch.data.entity.*
import kotlinx.coroutines.flow.Flow

class DispatchRepository(
    private val personDao: PersonDao,
    private val skillScoreDao: SkillScoreDao,
    private val productDao: ProductDao,
    private val productProcessDao: ProductProcessDao,
    private val assignmentDao: AssignmentDao
) {
    // Persons
    val allPersons: Flow<List<Person>> = personDao.getAll()
    val leavePersons: Flow<List<Person>> = personDao.getOnLeave()
    val availablePersons: Flow<List<Person>> = personDao.getAvailable()
    val allProcessNames: Flow<List<String>> = skillScoreDao.getAllProcessNames()
    val allProducts: Flow<List<Product>> = productDao.getAll()
    val allAssignments: Flow<List<Assignment>> = assignmentDao.getAll()

    suspend fun addPerson(name: String, employeeId: String = "") = personDao.insert(Person(name = name, employeeId = employeeId))
    suspend fun updatePerson(person: Person) = personDao.update(person)
    suspend fun deletePerson(person: Person) = personDao.delete(person)

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
    suspend fun getProcesses(productId: Int): Flow<List<ProductProcess>> = productProcessDao.getByProduct(productId)

    suspend fun getPersonById(id: Int): Person? = personDao.findById(id)

    suspend fun getScoresByPerson(personId: Int): List<SkillScore> = skillScoreDao.getByPersonOnce(personId)

    suspend fun getProcessesOnce(productId: Int): List<ProductProcess> = productProcessDao.getByProductOnce(productId)

    suspend fun updateAssignment(assignment: Assignment) = assignmentDao.update(assignment)
    suspend fun deleteDynamicAssignments() = assignmentDao.deleteDynamic()
    suspend fun clearAllAssignments() = assignmentDao.deleteAll()
    suspend fun getFixedAssignments(): List<Assignment> = assignmentDao.getFixedOnce()
    suspend fun insertAssignments(assignments: List<Assignment>) = assignmentDao.insertAll(assignments)

    suspend fun clearAll() {
        assignmentDao.deleteAll()
        productProcessDao.deleteAll()
        skillScoreDao.deleteAll()
        productDao.deleteAll()
        personDao.deleteAll()
    }
}
