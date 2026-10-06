import { createRouter, createWebHashHistory } from 'vue-router'

// 注意：main.js 里没有 app.use(router)，路由实例由 App.vue 直接驱动，
// 所以这里全部用静态 import —— <component :is> 拿到的是组件对象本身。
import IntakeView from '../views/IntakeView.vue'
import FeedView from '../views/FeedView.vue'
import PressView from '../views/PressView.vue'
import PanView from '../views/PanView.vue'
import BatchView from '../views/BatchView.vue'

const routes = [
  { path: '/', redirect: '/intake' },
  { path: '/intake', name: 'intake', component: IntakeView },
  { path: '/feed', name: 'feed', component: FeedView },
  { path: '/press', name: 'press', component: PressView },
  { path: '/pan', name: 'pan', component: PanView },
  { path: '/batch', name: 'batch', component: BatchView },
  { path: '/:pathMatch(.*)*', redirect: '/intake' }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
